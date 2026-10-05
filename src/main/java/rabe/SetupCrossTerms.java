package rabe;

import it.unisa.dia.gas.jpbc.Element;
import it.unisa.dia.gas.jpbc.ElementPowPreProcessing;
import it.unisa.dia.gas.plaf.jpbc.field.base.AbstractElementPowPreProcessing;
import java.math.BigInteger;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** Setup-only acceleration; no setup exponents are stored in the public parameters. */
final class SetupCrossTerms {
  static int workers(int n) {
    return Math.max(1, Math.min(n, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
  }

  static byte[][][][] generate(Element g, BigInteger order, BigInteger[] ts,
      BigInteger[][] us, int workers, IntConsumer progress) {
    int n = ts.length, u = us[0].length;
    byte[][][][] out = new byte[n][n][u][];
    if (n == 1) { progress.accept(1); return out; }
    // A_i = g^t_i, so A_i^u_ja = g^(t_i*u_ja mod p).
    // One larger fixed-base table replaces n separate small tables. JPBC's table is
    // read-only after construction; pow creates its own result and never mutates operands.
    ElementPowPreProcessing table = new AbstractElementPowPreProcessing(g, n >= 16 ? 8 : 5);
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(n, workers)));
    CompletionService<Integer> done = new ExecutorCompletionService<>(pool);
    try {
      for (int i = 0; i < n; i++) {
        final int row = i;
        done.submit(() -> {
          for (int j = 0; j < n; j++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (row != j)
              for (int a = 0; a < u; a++)
                out[row][j][a] = table.pow(ts[row].multiply(us[j][a]).mod(order)).toBytes();
          }
          return row;
        });
      }
      for (int i = 1; i <= n; i++) { done.take().get(); progress.accept(i); }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("公共参数生成被中断", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("公共参数生成失败", e.getCause());
    } finally {
      pool.shutdownNow();
    }
  }
}
