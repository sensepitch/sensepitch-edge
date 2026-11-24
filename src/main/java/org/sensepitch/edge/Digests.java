package org.sensepitch.edge;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/// SHA-256 and HMAC-SHA256 in one place, so the `catch` the JDK API demands exists only once.
///
/// @author Jens Wilke
public final class Digests {

  private static final String SHA_256 = "SHA-256";
  private static final String HMAC_SHA_256 = "HmacSHA256";

  private Digests() {}

  /// SHA-256 of the input. Excluded from the coverage report: every JDK provides SHA-256
  /// (`MessageDigest` doc, "Every implementation of the Java platform is required to support"),
  /// so the `catch` cannot be reached.
  @ExcludeFromJacocoGeneratedReport
  public static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance(SHA_256).digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /// HMAC-SHA256 of the payload. Excluded from the coverage report: every JDK provides
  /// `HmacSHA256` (`Mac` doc) and accepts a key of any non-empty length, so the `catch` cannot be
  /// reached with a non-empty key.
  @ExcludeFromJacocoGeneratedReport
  public static byte[] hmacSha256(byte[] key, byte[] payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA_256);
      mac.init(new SecretKeySpec(key, HMAC_SHA_256));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
