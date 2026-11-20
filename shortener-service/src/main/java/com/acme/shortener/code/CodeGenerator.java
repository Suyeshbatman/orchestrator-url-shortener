package com.acme.shortener.code;

import java.util.function.LongSupplier;

/** id = nextval('link_id_seq') → Feistel permutation → fixed-length Base62. */
public final class CodeGenerator {

  private final LongSupplier idSource;
  private final FeistelPermutation permutation;
  private final int codeLength;

  public CodeGenerator(LongSupplier idSource, FeistelPermutation permutation, int codeLength) {
    if (permutation.domainSize() != Base62.spaceSize(codeLength)) {
      throw new IllegalArgumentException("permutation domain does not match code length");
    }
    this.idSource = idSource;
    this.permutation = permutation;
    this.codeLength = codeLength;
  }

  public GeneratedCode next() {
    long id = idSource.getAsLong();
    return new GeneratedCode(id, encode(id));
  }

  /** Deterministic: the same id always maps to the same code under the same secret. */
  public String encode(long id) {
    return Base62.encode(permutation.permute(id), codeLength);
  }

  public record GeneratedCode(long id, String code) {}
}
