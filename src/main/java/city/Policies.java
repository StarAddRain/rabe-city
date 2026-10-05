package city;

import java.math.BigInteger;
import java.util.*;
import rabe.Policy;

/** Boolean AND/OR -> LSSS. OR shares parent vector; AND splits it into two vectors. */
public final class Policies {
  static class Node {
    String op;
    int attr;
    Node l, r;

    Node(int a) {
      attr = a;
    }

    Node(String o, Node a, Node b) {
      op = o;
      l = a;
      r = b;
    }
  }

  private final List<String> tokens = new ArrayList<>();
  private int pos, dimensions = 1, universe;
  private final List<List<BigInteger>> rows = new ArrayList<>();
  private final List<Integer> labels = new ArrayList<>();

  private Policies(String s, int universe) {
    this.universe = universe;
    if (s.length() > 1000) throw new IllegalArgumentException("策略过长");
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "\\s*(A[0-9]+|AND|OR|&&|\\|\\||[()])", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(s);
    int end = 0;
    while (m.find()) {
      if (m.start() != end) throw new IllegalArgumentException("策略只支持 A1、AND、OR 和括号");
      tokens.add(m.group(1).toUpperCase(Locale.ROOT));
      end = m.end();
    }
    if (!s.substring(end).trim().isEmpty() || tokens.isEmpty())
      throw new IllegalArgumentException("策略格式错误");
  }

  public static Policy parse(String s, int universe) {
    Policies p = new Policies(s.trim(), universe);
    Node root = p.or();
    if (p.pos != p.tokens.size()) throw new IllegalArgumentException("策略括号或运算符错误");
    p.share(root, new ArrayList<>(Collections.singletonList(BigInteger.ONE)));
    if (p.rows.size() > 50) throw new IllegalArgumentException("策略最多 50 行");
    BigInteger[][] matrix = new BigInteger[p.rows.size()][p.dimensions];
    int[] rho = new int[p.rows.size()];
    for (int i = 0; i < rho.length; i++) {
      Arrays.fill(matrix[i], BigInteger.ZERO);
      for (int j = 0; j < p.rows.get(i).size(); j++) matrix[i][j] = p.rows.get(i).get(j);
      rho[i] = p.labels.get(i);
    }
    return new Policy(matrix, rho);
  }

  boolean eat(String... options) {
    if (pos >= tokens.size()) return false;
    for (String s : options)
      if (tokens.get(pos).equals(s)) {
        pos++;
        return true;
      }
    return false;
  }

  Node or() {
    Node n = and();
    while (eat("OR", "||")) n = new Node("OR", n, and());
    return n;
  }

  Node and() {
    Node n = leaf();
    while (eat("AND", "&&")) n = new Node("AND", n, leaf());
    return n;
  }

  Node leaf() {
    if (eat("(")) {
      Node n = or();
      if (!eat(")")) throw new IllegalArgumentException("缺少右括号");
      return n;
    }
    if (pos >= tokens.size() || !tokens.get(pos).startsWith("A") || tokens.get(pos).equals("AND"))
      throw new IllegalArgumentException("缺少属性");
    int a = Integer.parseInt(tokens.get(pos++).substring(1)) - 1;
    if (a < 0 || a >= universe) throw new IllegalArgumentException("属性编号超出范围");
    return new Node(a);
  }

  void share(Node n, List<BigInteger> v) {
    if (n.op == null) {
      rows.add(v);
      labels.add(n.attr);
      return;
    }
    if (n.op.equals("OR")) {
      share(n.l, new ArrayList<>(v));
      share(n.r, new ArrayList<>(v));
      return;
    }
    int col = dimensions++;
    List<BigInteger> left = new ArrayList<>(v), right = new ArrayList<>();
    while (left.size() <= col) left.add(BigInteger.ZERO);
    for (int i = 0; i <= col; i++) right.add(BigInteger.ZERO);
    left.set(col, BigInteger.ONE);
    right.set(col, BigInteger.ONE.negate());
    share(n.l, left);
    share(n.r, right);
  }
}
