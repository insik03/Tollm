-- ============================================================================
-- Tollm 운영(prod) DB 스키마 DDL
-- ----------------------------------------------------------------------------
-- 왜 이 파일이 필요한가:
--   prod 프로파일은 spring.jpa.hibernate.ddl-auto=validate 다. 즉 JPA가 스키마를
--   자동 생성/변경하지 않고, 엔티티와 실제 스키마가 다르면 "부팅을 실패"시킨다. 따라서
--   운영 DB에는 배포 전에 이 DDL을 사람이 검토해서 미리 적용해 두어야 앱이 뜬다.
--   (운영 스키마를 JPA가 마음대로 바꾸지 못하게 해 의도치 않은 변경/데이터 손실을 막는 원칙)
--
-- 생성 방법(재현):
--   엔티티 메타데이터에서 MySQL DDL을 뽑았다(로컬 DB 상태가 아니라 "현재 엔티티" 기준이라
--   컬럼 정밀도 등 최신 매핑이 그대로 반영된다). 예:
--     ./gradlew bootRun --args="\
--       --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create \
--       --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-source=metadata \
--       --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=db/schema.sql \
--       --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
--   엔티티(컬럼/인덱스/FK)를 바꾸면 이 파일도 다시 생성해 커밋할 것.
--
-- 배포 절차(README의 EC2 배포 항목과 연결):
--   1) 운영 MySQL에 tollm 스키마(데이터베이스) 생성
--   2) 이 파일을 적용:  mysql -u <user> -p tollm < db/schema.sql
--   3) 그 다음에 SPRING_PROFILES_ACTIVE=prod 로 앱 기동 (validate 통과 → 부팅 성공)
--
-- 주의: 제약/인덱스 이름 중 UK…/FK… 는 Hibernate가 자동 생성한 이름이다. validate는 이름이
--   아니라 "테이블·컬럼 존재/타입"만 검증하므로, DBA가 이름을 바꿔 적용해도 부팅에는 문제없다.
-- ============================================================================

create table api_key (
    created_at datetime(6),
    expired_at datetime(6),
    id bigint not null auto_increment,
    team_id bigint,
    user_id bigint not null,
    key_hash varchar(255) not null,
    prefix varchar(255) not null,
    status enum ('ACTIVE','REVOKED'),
    primary key (id)
) engine=InnoDB;

create table llm_model (
    input_price_per1m decimal(38,2),
    output_price_per1m decimal(38,2),
    id bigint not null auto_increment,
    provider_id bigint not null,
    name varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table provider (
    enabled bit not null,
    id bigint not null auto_increment,
    base_url varchar(255),
    name varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table request_log (
    cache_hit bit not null,
    cost decimal(19,8),
    input_tokens integer,
    output_tokens integer,
    status_code integer,
    api_key_id bigint,
    created_at datetime(6),
    id bigint not null auto_increment,
    latency_ms bigint,
    team_id bigint,
    user_id bigint not null,
    model varchar(255),
    provider_name varchar(255),
    primary key (id)
) engine=InnoDB;

create table team (
    id bigint not null auto_increment,
    name varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table team_invite (
    created_at datetime(6),
    expires_at datetime(6),
    id bigint not null auto_increment,
    team_id bigint not null,
    token varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table team_member (
    created_at datetime(6),
    id bigint not null auto_increment,
    team_id bigint,
    user_id bigint,
    team_role enum ('MEMBER','OWNER'),
    primary key (id)
) engine=InnoDB;

create table team_usage_quota (
    current_usage decimal(19,8),
    monthly_cost_limit decimal(19,8),
    reset_at date,
    id bigint not null auto_increment,
    team_id bigint not null,
    primary key (id)
) engine=InnoDB;

create table usage_quota (
    current_usage decimal(19,8),
    monthly_cost_limit decimal(19,8),
    reset_at date,
    id bigint not null auto_increment,
    user_id bigint not null,
    primary key (id)
) engine=InnoDB;

create table users (
    created_at datetime(6),
    id bigint not null auto_increment,
    email varchar(255) not null,
    password varchar(255) not null,
    role enum ('ADMIN','MEMBER') not null,
    primary key (id)
) engine=InnoDB;

alter table api_key
   add constraint UKc3kxypboi2preufocp17cterb unique (key_hash);

alter table provider
   add constraint UKcfgo93bl0v243co72ay26bs94 unique (name);

create index idx_log_user_created
   on request_log (user_id, created_at);

create index idx_log_team_created
   on request_log (team_id, created_at);

create index idx_log_apikey_created
   on request_log (api_key_id, created_at);

alter table team_invite
   add constraint UK5k4xo13vf5xtojv5hg0rrcua3 unique (token);

alter table team_usage_quota
   add constraint UKh0fl76a0ycjclblt8lqa7elqv unique (team_id);

alter table usage_quota
   add constraint UKguhbr8r1en82a4vyhu3kkwk16 unique (user_id);

alter table users
   add constraint UK6dotkott2kjsp8vw4d0m25fb7 unique (email);

alter table api_key
   add constraint FK9gr4y52j77foa5x3n9sk5nj1c
   foreign key (team_id)
   references team (id);

alter table api_key
   add constraint FKcw5ugt7te6atpf6jgnrf6t20h
   foreign key (user_id)
   references users (id);

alter table llm_model
   add constraint FKmbd02d40ghhq68q2rwsrdrldf
   foreign key (provider_id)
   references provider (id);

alter table request_log
   add constraint FKkie2q5mw3y88h5obw7ugowsvy
   foreign key (api_key_id)
   references api_key (id);

alter table request_log
   add constraint FKskogpe7suf09yw5q01dhnqdw
   foreign key (team_id)
   references team (id);

alter table request_log
   add constraint FKijf5kbwlrftkab1ac75123rjn
   foreign key (user_id)
   references users (id);

alter table team_invite
   add constraint FKp9wtc66edyxm3i0mshq108h77
   foreign key (team_id)
   references team (id);

alter table team_member
   add constraint FK9ubp79ei4tv4crd0r9n7u5i6e
   foreign key (team_id)
   references team (id);

alter table team_member
   add constraint FKcwlc6ivvbf5svve5fmbg71vks
   foreign key (user_id)
   references users (id);

alter table team_usage_quota
   add constraint FKft2p2xnjcha79oe2066bwkttb
   foreign key (team_id)
   references team (id);

alter table usage_quota
   add constraint FKmn46g2samy6cf1m0oqaxwx4kt
   foreign key (user_id)
   references users (id);

-- [BYOK] 사용자가 등록한 프로바이더 키(암호화 저장). (user_id, provider)당 1개.
create table provider_credential (
    id bigint not null auto_increment,
    user_id bigint not null,
    provider varchar(255) not null,
    encrypted_key varchar(1024) not null,
    key_preview varchar(255) not null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

alter table provider_credential
   add constraint uk_provider_credential_user_provider unique (user_id, provider);

alter table provider_credential
   add constraint FK_provider_credential_user
   foreign key (user_id)
   references users (id);
