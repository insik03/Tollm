# Tollm AWS EC2 배포 가이드 (11주차 과제)

이 문서는 수업 자료(11주차 - AWS 배포)와 참고 gist의 방식을 그대로 따르되, Tollm에 필요한
부분(Redis, 팀/프록시용 DB 스키마, 프로바이더 키)을 더한 배포 절차다.

- 방식: **Ubuntu EC2 1대**에 apt로 직접 설치 → 서버에서 `git clone` → `./gradlew build` → 실행
- 인프라: MySQL + Redis도 같은 EC2에 apt로 설치 (관리형 RDS/ElastiCache 미사용 — 과제 수준·비용 고려)
- 앱 실행: systemd 서비스로 등록 (과제 예시의 foreground `java -jar`를 자동시작/자동재시작으로 개선)
- 앞단: Nginx 리버스 프록시(80 → 8080), (선택) 무료 도메인 + HTTPS

> ⚠️ AWS 콘솔 작업(계정, 인스턴스 생성, 보안그룹, 키페어)은 **본인 계정에서 직접** 해야 한다.
> 이 가이드는 그 콘솔 단계와, 접속 후 서버에서 칠 명령을 순서대로 정리한 것이다.

---

## 0. 사전 준비

- [ ] AWS 계정 (프리티어면 충분)
- [ ] 이 저장소가 GitHub에 push되어 있어야 한다 (서버에서 `git clone` 하므로)
- [ ] OpenAI / Anthropic API 키 (프록시가 실제로 동작하려면 필요)

---

## 1. EC2 인스턴스 생성 (AWS 콘솔)

1. EC2 → **인스턴스 시작(Launch instance)**
2. 이름: `tollm`
3. AMI: **Ubuntu Server 22.04 LTS** (또는 24.04) — 과제 gist가 apt(Ubuntu) 기준
4. 인스턴스 유형: **t2.micro** 또는 **t3.micro** (프리티어)
   - 메모리 1GB라 빌드가 빡빡하다 → 아래 3단계에서 **스왑 2GB**를 꼭 잡는다
5. 키 페어: **새 키 페어 생성** → 이름 `tollm-key` → **.pem 다운로드** (한 번만 받을 수 있으니 잘 보관)
6. 스토리지: 기본 8GB → **20GB**로 늘려두면 빌드 캐시/로그에 여유 (프리티어 30GB까지 무료)
7. 네트워크 설정 → 보안 그룹은 아래 2단계대로 (지금 만들거나, 만든 뒤 편집)
8. **인스턴스 시작**

## 2. 보안 그룹 (방화벽) 설정

인바운드 규칙에 다음만 연다:

| 유형 | 포트 | 소스 | 이유 |
|------|------|------|------|
| SSH | 22 | 내 IP | 서버 접속 (전체 공개보다 내 IP만 권장) |
| HTTP | 80 | 0.0.0.0/0, ::/0 | Nginx |
| HTTPS | 443 | 0.0.0.0/0, ::/0 | (선택) HTTPS 쓸 때 |

> ❌ **8080(앱), 3306(MySQL), 6379(Redis)는 외부에 열지 않는다.** 앱은 Nginx 뒤에 두고,
> DB/Redis는 같은 서버 안에서 localhost로만 접근한다. 외부에 열면 무인증 접근 위험이 크다.

## 3. SSH 접속 + 서버 기본 설정

로컬(내 PC)에서 접속. 퍼블릭 IP는 콘솔 인스턴스 상세에 있다.

```bash
# .pem 권한 조정 (mac/linux). Windows는 MobaXterm 등 GUI 툴을 써도 된다(과제 17p 참고)
chmod 400 tollm-key.pem
ssh -i tollm-key.pem ubuntu@<퍼블릭_IP>
```

접속 후 서버에서:

```bash
# 패키지 목록 업데이트
sudo apt-get update

# 스왑 2GB (1GB 인스턴스에서 gradlew build가 메모리 부족으로 죽는 것 방지 - gist 4단계)
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab   # 재부팅 후에도 유지
free -h   # Swap 2Gi 확인

# Java 21 (Amazon Corretto) - 이 프로젝트는 Java 21 toolchain (gist 3단계)
wget -O - https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg && \
echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | sudo tee /etc/apt/sources.list.d/corretto.list
sudo apt-get update
sudo apt-get install -y java-21-amazon-corretto-jdk
java -version   # 21 확인
```

## 4. MySQL 설치 + Tollm DB/계정/스키마

```bash
# 설치 (gist 8단계)
sudo apt install -y mysql-server
sudo systemctl enable mysql
sudo systemctl start mysql

# 앱 전용 DB와 계정 생성 (root 대신 최소 권한 계정 - 재검수 지적 반영)
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS tollm CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'tollm'@'localhost' IDENTIFIED BY '여기_강한_비밀번호로_바꾸기';
GRANT ALL PRIVILEGES ON tollm.* TO 'tollm'@'localhost';
FLUSH PRIVILEGES;
SQL
```

> DB/Redis가 같은 서버 안에 있으므로 gist의 "외부 접속 허용(bind-address 주석)" 단계는 하지 않는다.
> (localhost 접근만 필요 → 외부 노출은 오히려 보안 위험)

스키마는 코드를 내려받은 뒤(6단계) 적용한다. prod 프로파일은 `ddl-auto=validate`라
스키마가 미리 있어야 부팅에 성공한다.

## 5. Redis 설치

```bash
sudo apt install -y redis-server
sudo systemctl enable redis-server
sudo systemctl start redis-server
redis-cli ping   # PONG 확인
```

## 6. 코드 내려받기 + 스키마 적용 + 빌드

```bash
cd ~
git clone https://github.com/insik03/Tollm.git
cd Tollm

# 운영 DB 스키마 적용 (저장소에 커밋된 검토된 DDL). 위에서 정한 tollm 계정 비밀번호 입력
mysql -u tollm -p tollm < db/schema.sql
# 확인: 테이블이 생겼는지
mysql -u tollm -p -e "USE tollm; SHOW TABLES;"

# 빌드 (테스트 포함 - 처음엔 의존성 다운로드로 몇 분 걸린다)
./gradlew build
# 빌드 산출물(실행 jar) 확인 - 실행용은 -plain 이 붙지 않은 파일
ls -lh build/libs/
# 예: tollm-0.0.1-SNAPSHOT.jar (실행용), tollm-0.0.1-SNAPSHOT-plain.jar (라이브러리용, 실행X)
```

## 7. 환경변수 + systemd 등록 + 기동

```bash
# 환경변수 파일 준비 (git에 올라가지 않는 실제 비밀값)
sudo mkdir -p /etc/tollm
sudo cp deploy/tollm.env.example /etc/tollm/tollm.env
sudo nano /etc/tollm/tollm.env    # DB_PASSWORD, JWT_SECRET, OPENAI/ANTHROPIC 키 채우기
sudo chmod 600 /etc/tollm/tollm.env

# JWT_SECRET 생성 예시 (32바이트 이상 랜덤)
openssl rand -base64 48

# systemd 서비스 등록
sudo cp deploy/tollm.service /etc/systemd/system/tollm.service
sudo systemctl daemon-reload
sudo systemctl enable tollm
sudo systemctl start tollm

# 상태/로그 확인
sudo systemctl status tollm
journalctl -u tollm -f     # 'Started TollmApplication' 나오면 성공, Ctrl+C로 빠져나옴

# 기동 확인 (서버 내부에서)
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

> jar 파일명이 다르면 `/etc/systemd/system/tollm.service`의 `ExecStart` 경로를 `ls build/libs`
> 결과에 맞춰 수정한 뒤 `sudo systemctl daemon-reload && sudo systemctl restart tollm`.

## 8. Nginx 리버스 프록시

```bash
sudo apt install -y nginx

# 저장소의 설정을 적용 (80 → 127.0.0.1:8080)
sudo cp deploy/nginx-tollm.conf /etc/nginx/sites-available/tollm
sudo ln -sf /etc/nginx/sites-available/tollm /etc/nginx/sites-enabled/tollm
sudo rm -f /etc/nginx/sites-enabled/default   # 기본 환영페이지 비활성화
sudo nginx -t                                 # 문법 검사 OK 확인
sudo systemctl restart nginx
```

이제 브라우저에서 `http://<퍼블릭_IP>` 로 접속하면 Tollm 대시보드가 뜬다.

## 9. (선택) 무료 도메인 + HTTPS

- 무료 도메인: 내도메인.한국(https://xn--220b31d95hq8o.xn--3e0b707e/)에서 발급 후,
  A 레코드를 EC2 **퍼블릭 IP**로 연결 (과제 27~28p). 발급 도메인을
  `deploy/nginx-tollm.conf`의 `server_name`에 넣고 `sudo systemctl restart nginx`.
- HTTPS (도메인 연결 후):
  ```bash
  sudo snap install --classic certbot
  sudo certbot --nginx        # 도메인 입력하면 인증서 자동 발급 + 443 설정
  ```

## 10. 동작 확인 (외부에서)

브라우저 또는 로컬 터미널에서 (`<HOST>`는 퍼블릭 IP 또는 도메인):

```bash
# 헬스체크
curl http://<HOST>/actuator/health

# 회원가입 → 로그인 (필수 요구사항: 인증 기능)
curl -X POST http://<HOST>/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"demo@test.com","password":"Passw0rd!23"}'
curl -X POST http://<HOST>/auth/login -H "Content-Type: application/json" \
  -d '{"email":"demo@test.com","password":"Passw0rd!23"}'
```

또는 대시보드(`http://<HOST>`)에서 회원가입 → 키 발급 → 채팅 테스트까지 시연.

---

## 자주 겪는 문제

- **빌드가 killed / 멈춤**: 메모리 부족. 스왑 2GB(3단계)를 잡았는지 `free -h`로 확인.
- **앱이 안 뜨고 로그에 `Could not resolve placeholder`**: `/etc/tollm/tollm.env`에 값이 비었다.
  prod는 모든 키가 있어야 부팅된다(fail-fast). `journalctl -u tollm -e`로 어떤 키인지 확인.
- **`Communications link failure`**: MySQL이 안 떴거나 DB_URL/계정이 틀림. `sudo systemctl status mysql`,
  `mysql -u tollm -p` 로 접속 테스트.
- **validate 실패(`missing table`)**: 6단계의 `db/schema.sql` 적용을 건너뛴 것. 적용 후 재기동.
- **502 Bad Gateway(Nginx)**: 앱(8080)이 안 떠 있음. `sudo systemctl status tollm`.
- **프록시 요청만 401/PROVIDER_ERROR**: 프로바이더 키 미설정/오타. `/etc/tollm/tollm.env` 확인 후
  `sudo systemctl restart tollm`.
