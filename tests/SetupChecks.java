package rabe;

import it.unisa.dia.gas.jpbc.*;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/** Same random inputs for both implementations; compare every serialized cross term. */
public final class SetupChecks {
  public static void main(String[] args) {
    int n = args.length == 0 ? 16 : Integer.parseInt(args[0]), u = 50;
    Pairing pairing = PairingFactory.getPairing("parameters.properties");
    Element g = pairing.getG1().newRandomElement().getImmutable();
    BigInteger p = pairing.getZr().getOrder();
    SecureRandom random = new SecureRandom();
    BigInteger[] ts = new BigInteger[n];
    BigInteger[][] us = new BigInteger[n][u];
    for (int i = 0; i < n; i++) {
      ts[i] = new BigInteger(p.bitLength(), random).mod(p.subtract(BigInteger.ONE)).add(BigInteger.ONE);
      for (int a = 0; a < u; a++) us[i][a] = new BigInteger(p.bitLength(), random).mod(p);
    }
    // Boundary exponents as well as random full-length scalars.
    us[0][0] = BigInteger.ZERO; us[0][1] = BigInteger.ONE; us[0][2] = p.subtract(BigInteger.ONE);
    byte[][][][] reference = new byte[n][n][u][];
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      ElementPowPreProcessing table = g.duplicate().pow(ts[i]).getElementPowPreProcessing();
      for (int j = 0; j < n; j++) if (i != j)
        for (int a = 0; a < u; a++) reference[i][j][a] = table.pow(us[j][a]).toBytes();
    }
    double original = (System.nanoTime() - start) / 1e9;
    System.out.printf("Original %dx%d: %.3f s%n", n, u, original);
    for (int threads : new int[] {1, SetupCrossTerms.workers(n)}) {
      start = System.nanoTime();
      final int[] progress = {0};
      byte[][][][] actual = SetupCrossTerms.generate(g, p, ts, us, threads, count -> {
        if (count != ++progress[0]) throw new AssertionError("non-monotonic progress");
      });
      double seconds = (System.nanoTime() - start) / 1e9;
      int checked = 0;
      for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int a = 0; a < u; a++) {
        if (!Arrays.equals(reference[i][j][a], actual[i][j][a])) throw new AssertionError("cross-term mismatch");
        checked++;
      }
      if (progress[0] != n) throw new AssertionError("incomplete progress");
      System.out.printf("Optimized threads=%d: %.3f s, speedup=%.2fx, exact byte comparisons=%d%n",
          threads, seconds, original / seconds, checked);
    }
  }
}
