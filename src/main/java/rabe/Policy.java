package rabe;

import java.math.BigInteger;
import java.util.*;

/** LSSS over Z_p. Rows retain their identity even with repeated attribute labels. */
public final class Policy {
  public final BigInteger[][] matrix;
  public final int[] rho;

  public Policy(BigInteger[][] m, int[] r) {
    if (m.length == 0 || m.length != r.length || m[0].length == 0)
      throw new IllegalArgumentException("matrix shape");
    matrix = new BigInteger[m.length][];
    rho = r.clone();
    for (int i = 0; i < m.length; i++) {
      if (m[i].length != m[0].length) throw new IllegalArgumentException("ragged matrix");
      matrix[i] = m[i].clone();
    }
  }

  public static Policy and(int size) {
    BigInteger[][] m = new BigInteger[size][size];
    int[] r = new int[size];
    for (int i = 0; i < size; i++) {
      Arrays.fill(m[i], BigInteger.ZERO);
      r[i] = i;
      if (i == 0) m[i][0] = BigInteger.ONE;
      if (i > 0) m[i][i] = BigInteger.ONE;
      if (i + 1 < size) m[i][i + 1] = BigInteger.ONE.negate();
    }
    return new Policy(m, r);
  }

  public static Policy or(int a, int b) {
    return new Policy(new BigInteger[][] {{BigInteger.ONE}, {BigInteger.ONE}}, new int[] {a, b});
  }
  /** Solves M_I^T omega = (1,0,...,0) using modular Gaussian elimination. */
  public BigInteger[] reconstruct(Set<Integer> attributes, BigInteger p) {
    List<Integer> selected = new ArrayList<>();
    for (int i = 0; i < rho.length; i++) if (attributes.contains(rho[i])) selected.add(i);
    int n = selected.size(), d = matrix[0].length;
    BigInteger[][] a = new BigInteger[d][n + 1];
    for (int row = 0; row < d; row++) {
      for (int c = 0; c < n; c++) a[row][c] = matrix[selected.get(c)][row].mod(p);
      a[row][n] = row == 0 ? BigInteger.ONE : BigInteger.ZERO;
    }
    int pivotRow = 0;
    int[] pivot = new int[Math.min(d, n)];
    for (int col = 0; col < n && pivotRow < d; col++) {
      int k = pivotRow;
      while (k < d && a[k][col].signum() == 0) k++;
      if (k == d) continue;
      BigInteger[] tmp = a[k];
      a[k] = a[pivotRow];
      a[pivotRow] = tmp;
      BigInteger inv = a[pivotRow][col].modInverse(p);
      for (int c = col; c <= n; c++) a[pivotRow][c] = a[pivotRow][c].multiply(inv).mod(p);
      for (int row = 0; row < d; row++)
        if (row != pivotRow) {
          BigInteger factor = a[row][col];
          for (int c = col; c <= n; c++)
            a[row][c] = a[row][c].subtract(factor.multiply(a[pivotRow][c])).mod(p);
        }
      pivot[pivotRow++] = col;
    }
    for (int row = pivotRow; row < d; row++) if (a[row][n].signum() != 0) return null;
    BigInteger[] out = new BigInteger[rho.length];
    Arrays.fill(out, BigInteger.ZERO);
    for (int row = 0; row < pivotRow; row++) out[selected.get(pivot[row])] = a[row][n];
    return out;
  }

  public BigInteger[] shares(BigInteger[] vector, BigInteger p) {
    BigInteger[] out = new BigInteger[rho.length];
    for (int i = 0; i < rho.length; i++) {
      out[i] = BigInteger.ZERO;
      for (int j = 0; j < vector.length; j++)
        out[i] = out[i].add(matrix[i][j].multiply(vector[j])).mod(p);
    }
    return out;
  }
}
