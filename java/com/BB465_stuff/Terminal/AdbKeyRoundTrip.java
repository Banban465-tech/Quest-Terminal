package com.BB465_stuff.Terminal;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Exercises exactly what the ADB dialog does on the headset: generate a key,
 * write it out as a PKCS#8 PEM, then load it back and re-serialise. If the
 * round trip is stable the app can keep one identity across launches.
 */
public class AdbKeyRoundTrip {
    public static void main(String[] a) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "adbrt");
        dir.mkdirs();
        File f = new File(dir, "adbkey");

        AdbKey k1 = AdbKey.generate();
        String b64a = k1.encodeBase64();
        System.out.println("generated : " + b64a.length() + " b64 chars");

        // this is the exact PEM the app writes
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(k1.priv.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        FileWriter w = new FileWriter(f);
        w.write(pem);
        w.close();
        System.out.println("pem written: " + f.length() + " bytes");

        AdbKey k2 = AdbKey.fromPem(f);
        String b64b = k2.encodeBase64();
        System.out.println("reloaded  : " + b64b.length() + " b64 chars");
        System.out.println("same key  : " + b64a.equals(b64b));

        // the reloaded key must still produce a verifiable signature
        byte[] tok = new byte[20];
        for (int i = 0; i < 20; i++) tok[i] = (byte) (i * 7 + 1);
        byte[] s1 = k1.signToken(tok);
        byte[] s2 = k2.signToken(tok);
        System.out.println("sig len   : " + s1.length + " / " + s2.length);
        System.out.println("sig match : " + Arrays.equals(s1, s2));

        // verify the signature the way adbd does: RSA_verify(NID_sha1, ...)
        java.security.PublicKey pub = k2.pub;
        java.security.Signature v = java.security.Signature.getInstance("SHA1withRSA");
        v.initVerify(pub);
        v.update(tok);
        System.out.println("verifies  : " + v.verify(s1));
    }
}
