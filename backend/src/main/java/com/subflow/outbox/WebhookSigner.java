package com.subflow.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 웹훅 HMAC-SHA256 서명.
 *
 * <p>수신 측은 {@code X-SubFlow-Signature: t=<epochSeconds>,v1=<hex>} 헤더를 그대로 다시 계산해 비교한다.
 * 본문이 1바이트라도 바뀌면 서명이 달라지므로 위조·변조를 막을 수 있고, 시각(t)으로 재전송 공격을 제한한다.
 */
public final class WebhookSigner {

    public static final String HEADER = "X-SubFlow-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    /** 서명 문자열({@code t=...,v1=...)})을 만든다. */
    public static String sign(String secret, Instant timestamp, String body) {
        long epochSeconds = timestamp.getEpochSecond();
        String signature = hmacHex(secret, epochSeconds + "." + body);
        return "t=" + epochSeconds + ",v1=" + signature;
    }

    /** 서명을 검증한다. 허용 오차를 넘은 시각의 요청은 거부한다. */
    public static boolean verify(String secret, String signatureHeader, String body,
                                 Instant now, Duration tolerance) {
        if (signatureHeader == null || secret == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length != 2) {
                continue;
            }
            if (keyValue[0].trim().equals("t")) {
                timestamp = Long.parseLong(keyValue[1].trim());
            } else if (keyValue[0].trim().equals("v1")) {
                signature = keyValue[1].trim();
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        Instant signedAt = Instant.ofEpochSecond(timestamp);
        if (tolerance != null && signedAt.isBefore(now.minus(tolerance))) {
            return false;
        }
        String expected = hmacHex(secret, timestamp + "." + body);
        return constantTimeEquals(expected, signature);
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 서명 생성에 실패했습니다.", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
