package com.tollm.global.auth;

import com.tollm.global.config.TollmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private CryptoService crypto(String key) {
        TollmProperties props = new TollmProperties();
        props.getSecurity().setByokEncryptionKey(key);
        return new CryptoService(props);
    }

    @Test
    void 암호화한_값을_복호화하면_원문이_나온다() {
        CryptoService crypto = crypto("test-encryption-key");
        String plain = "sk-proj-secret-api-key-12345";

        String enc = crypto.encrypt(plain);

        assertThat(enc).isNotEqualTo(plain);       // 평문이 그대로 노출되지 않음
        assertThat(crypto.decrypt(enc)).isEqualTo(plain);
    }

    // GCM은 매 암호화마다 랜덤 IV를 써서, 같은 평문도 매번 다른 암호문이 된다(패턴 노출 방지)
    @Test
    void 같은_평문도_매번_다른_암호문이_된다() {
        CryptoService crypto = crypto("test-encryption-key");

        assertThat(crypto.encrypt("hello")).isNotEqualTo(crypto.encrypt("hello"));
    }

    // 다른 키로는 복호화 불가 - 암호화 키가 유출되지 않는 한 DB만으론 원문을 못 얻는다
    @Test
    void 다른_키로는_복호화할_수_없다() {
        String enc = crypto("key-A").encrypt("secret");

        assertThatThrownBy(() -> crypto("key-B").decrypt(enc))
                .isInstanceOf(IllegalStateException.class);
    }

    // GCM 인증 태그 덕분에 암호문이 변조되면 복호화 단계에서 걸린다
    @Test
    void 변조된_암호문은_복호화에_실패한다() {
        CryptoService crypto = crypto("test-encryption-key");
        String enc = crypto.encrypt("secret-value-to-encrypt");
        char[] chars = enc.toCharArray();
        chars[5] = (chars[5] == 'A') ? 'B' : 'A'; // 중간 문자 하나 변조
        String tampered = new String(chars);

        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}
