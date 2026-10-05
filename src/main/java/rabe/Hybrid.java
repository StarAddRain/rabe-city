package rabe;

import it.unisa.dia.gas.jpbc.Element;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional byte-data envelope. AES-GCM is an engineering addition, not in the supplied
 * Construction.
 */
public final class Hybrid {
  private Hybrid() {}

  public static final class Envelope {
    public final Scheme.Ciphertext ciphertext;
    public final byte[] nonce, payload;

    public Envelope(Scheme.Ciphertext c, byte[] n, byte[] p) {
      ciphertext = c;
      nonce = n.clone();
      payload = p.clone();
    }

    public Envelope withUpdatedCiphertext(Scheme.Ciphertext c) {
      return new Envelope(c, nonce, payload);
    }
  }

  public static SecretKeySpec key(Element m) throws GeneralSecurityException {
    MessageDigest h = MessageDigest.getInstance("SHA-256");
    h.update("RABE-byte-envelope-v1".getBytes(StandardCharsets.UTF_8));
    h.update(m.toBytes());
    return new SecretKeySpec(Arrays.copyOf(h.digest(), 16), "AES");
  }

  public static Envelope encrypt(
      Scheme s,
      Scheme.Registry r,
      int sender,
      Policy p,
      Set<Integer> claimed,
      java.math.BigInteger q,
      byte[] plaintext)
      throws GeneralSecurityException {
    Element m = s.message();
    Scheme.Ciphertext c = s.signcrypt(r, sender, p, m, claimed, q);
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
    aes.init(Cipher.ENCRYPT_MODE, key(m), new GCMParameterSpec(128, nonce));
    return new Envelope(c, nonce, aes.doFinal(plaintext));
  }
  /** Local-only final step: cloud Transform has already been performed remotely. */
  public static byte[] decryptTransformed(
      Scheme s,
      java.math.BigInteger secret,
      Scheme.Transformed transformed,
      byte[] nonce,
      byte[] payload)
      throws GeneralSecurityException {
    Element m = s.decrypt(secret, transformed);
    Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
    aes.init(Cipher.DECRYPT_MODE, key(m), new GCMParameterSpec(128, nonce));
    try {
      return aes.doFinal(payload);
    } catch (javax.crypto.AEADBadTagException invalid) {
      return null;
    }
  }
  /** Returns null for policy rejection, wrong/revoked key or payload authentication failure. */
  public static byte[] decrypt(
      Scheme s,
      Scheme.Registry r,
      int receiver,
      Element ri,
      Policy reverse,
      java.math.BigInteger secret,
      Envelope envelope)
      throws GeneralSecurityException {
    Scheme.Transformed transformed = s.transform(r, receiver, ri, envelope.ciphertext, reverse);
    if (transformed == null) return null;
    Element m = s.decrypt(secret, transformed);
    Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
    aes.init(Cipher.DECRYPT_MODE, key(m), new GCMParameterSpec(128, envelope.nonce));
    try {
      return aes.doFinal(envelope.payload);
    } catch (javax.crypto.AEADBadTagException invalid) {
      return null;
    }
  }
}
