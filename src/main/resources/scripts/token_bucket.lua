-- 토큰 버킷 레이트리밋: "리필 계산 + 차감"을 한 스크립트로 원자적으로 실행한다.
-- 애플리케이션 코드에서 GET(남은 토큰 조회) 후 SET(차감된 값 저장)을 따로 하면 그 사이에
-- 다른 요청(다른 스레드/다른 서버 인스턴스)이 끼어들어 같은 값을 읽고 같은 값을 써버리는
-- 레이스 컨디션이 생긴다. Redis는 Lua 스크립트 하나를 단일 명령처럼 원자적으로 실행하므로
-- (스크립트 실행 도중 다른 클라이언트의 명령이 끼어들 수 없음) 이 문제가 사라진다.
--
-- KEYS[1] : 버킷 키 ("bucket:{userId}")
-- ARGV[1] : capacity      (버킷 최대 토큰 수)
-- ARGV[2] : refillPerSec  (초당 리필 토큰 수)
-- ARGV[3] : now           (현재 시각, epoch millis)
-- ARGV[4] : requested     (이번 요청이 소비할 토큰 수 - 보통 1)
--
-- 반환값: 1 = 허용(토큰 차감됨), 0 = 거부(토큰 부족 -> 호출부에서 429로 변환)

local capacity = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local last_ts = tonumber(bucket[2])

-- 최초 요청(키 없음): 버킷을 가득 채운 상태로 시작
if tokens == nil or last_ts == nil then
    tokens = capacity
    last_ts = now
end

-- 경과 시간(초)만큼 리필하되 capacity를 넘지 않게 클램프
local elapsed_sec = math.max(0, now - last_ts) / 1000
tokens = math.min(capacity, tokens + elapsed_sec * refill_per_sec)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- 유휴 사용자의 버킷 키가 Redis에 영구히 남지 않도록 TTL을 걸어둔다.
-- capacity/refill 설정값 기준으로 버킷이 완전히 다시 찰 시간보다 충분히 길게 잡으면 되므로 1시간이면 넉넉하다.
redis.call('EXPIRE', KEYS[1], 3600)

return allowed
