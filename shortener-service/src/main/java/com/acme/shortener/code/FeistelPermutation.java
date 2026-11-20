package com.acme.shortener.code;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed bijection on [0, domainSize) that makes sequential ids non-enumerable (ADR-0001).
 *
 * <p>A balanced Feistel network over 2 * halfBits bits is invertible whatever the round function,
 * so it can never produce a collision. Round function: the first halfBits bits of
 * HMAC-SHA256(secret, round || half). Since 2^(2 * halfBits) is a little larger than domainSize,
 * outputs outside the domain are permuted again (cycle-walking) until they land inside it, which
 * keeps the bijection on the subset. This scrambles ids; it is not encryption or access control.
 */
public final class FeistelPermutation {

  private static final String HMAC = "HmacSHA256";
  private static final int MIN_SECRET_BYTES = 32;

  private final long domainSize;
  private final int halfBits;
  private final long halfMask;
  private final int rounds;
  private final ThreadLocal<Mac> mac;

  public FeistelPermutation(byte[] secret, long domainSize, int rounds) {
    Objects.requireNonNull(secret, "secret");
    if (secret.length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException("secret must be at least " + MIN_SECRET_BYTES + " bytes");
    }
    if (domainSize < 2) {
      throw new IllegalArgumentException("domainSize must be >= 2");
    }
    if (rounds < 3) {
      throw new IllegalArgumentException("rounds must be >= 3");
    }
    int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
    this.halfBits = (bits + 1) / 2;
    if (halfBits > 31) {
      throw new IllegalArgumentException("domainSize too large");
    }
    this.domainSize = domainSize;
    this.halfMask = (1L << halfBits) - 1;
    this.rounds = rounds;
    byte[] key = secret.clone();
    this.mac = ThreadLocal.withInitial(() -> newMac(key));
  }

  /** Permutation over the full Base62 code space of the given length (62^7 for 7 characters). */
  public static FeistelPermutation forCodeLength(String secret, int codeLength, int rounds) {
    return new FeistelPermutation(
        secret.getBytes(StandardCharsets.UTF_8), Base62.spaceSize(codeLength), rounds);
  }

  public long domainSize() {
    return domainSize;
  }

  public long permute(long x) {
    checkInDomain(x);
    long y = encryptBlock(x);
    while (y >= domainSize) {
      y = encryptBlock(y);
    }
    return y;
  }

  public long inverse(long y) {
    checkInDomain(y);
    long x = decryptBlock(y);
    while (x >= domainSize) {
      x = decryptBlock(x);
    }
    return x;
  }

  private long encryptBlock(long block) {
    long left = block >>> halfBits;
    long right = block & halfMask;
    for (int round = 0; round < rounds; round++) {
      long next = left ^ roundFunction(round, right);
      left = right;
      right = next;
    }
    return (left << halfBits) | right;
  }

  private long decryptBlock(long block) {
    long left = block >>> halfBits;
    long right = block & halfMask;
    for (int round = rounds - 1; round >= 0; round--) {
      long previous = right ^ roundFunction(round, left);
      right = left;
      left = previous;
    }
    return (left << halfBits) | right;
  }

  private long roundFunction(int round, long half) {
    byte[] input =
        ByteBuffer.allocate(Integer.BYTES + Long.BYTES).putInt(round).putLong(half).array();
    byte[] digest = mac.get().doFinal(input);
    return ByteBuffer.wrap(digest).getLong() >>> (64 - halfBits);
  }

  private void checkInDomain(long value) {
    if (value < 0 || value >= domainSize) {
      throw new IllegalArgumentException("value " + value + " outside [0, " + domainSize + ")");
    }
  }

  private static Mac newMac(byte[] key) {
    try {
      Mac m = Mac.getInstance(HMAC);
      m.init(new SecretKeySpec(key, HMAC));
      return m;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
