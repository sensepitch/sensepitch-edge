package org.sensepitch.edge;

import lombok.Builder;

/// Configuration of the HTTP header fingerprint, see [Sp1Fingerprint].
///
/// @param enable compute the fingerprint of every request and send it to the upstream in the
///   {@value FingerprintHandler#SP1_HEADER} request header
/// @param raw additionally send the unhashed input in the {@value
///   FingerprintHandler#SP1_RAW_HEADER} request header, to inspect what a client sends.
///   Has no effect when `enable` is not set.
@Builder(toBuilder = true)
public record Sp1FingerprintConfig(boolean enable, boolean raw) {

  public static final Sp1FingerprintConfig DEFAULT = Sp1FingerprintConfig.builder().build();
}
