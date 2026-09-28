package com.BB465_stuff.Terminal;

import java.io.File;
import java.util.Arrays;
import java.util.Base64;

/**
 * Self-test: serialise the PC's existing adb key with AdbKey and compare against
 * the blob adb itself wrote to adbkey.pub. A byte-exact match proves the 524-byte
 * layout, the Montgomery parameters and the byte order are all correct.
 */
public class AdbKeyTest {
    public static void main(String[] a) throws Exception {
        File priv = new File(System.getProperty("user.home") + "\\.android\\adbkey");
        File pubF = new File(System.getProperty("user.home") + "\\.android\\adbkey.pub");
        if (!priv.isFile() || !pubF.isFile()) {
            System.out.println("SKIP: no adbkey on this PC");
            return;
        }
        AdbKey k = AdbKey.fromPem(priv);

        byte[] mine = k.encode();
        String mineB64 = Base64.getEncoder().encodeToString(mine);

        String realLine = new String(java.nio.file.Files.readAllBytes(pubF.toPath()))
                .trim().split("\\s+")[0];
        byte[] real = Base64.getDecoder().decode(realLine);

        System.out.println("ours : " + mine.length + " bytes");
        System.out.println("adb  : " + real.length + " bytes");
        System.out.println("match: " + Arrays.equals(mine, real));
        if (!Arrays.equals(mine, real)) {
            for (int i = 0; i < Math.max(mine.length, real.length); i++) {
                byte m = i < mine.length ? mine[i] : 0;
                byte r = i < real.length ? real[i] : 0;
                if (m != r) {
                    System.out.printf("  first diff at %d: ours=%02x adb=%02x%n", i, m, r);
                    break;
                }
            }
            System.out.println("  ours b64: " + mineB64.substring(0, 40) + "...");
            System.out.println("  adb  b64: " + realLine.substring(0, 40) + "...");
        } else {
            System.out.println("  b64 identical: " + mineB64.equals(realLine));
        }

        // also exercise a freshly generated key end to end
        AdbKey fresh = AdbKey.generate();
        byte[] f = fresh.encode();
        System.out.println("fresh key encodes to " + f.length + " bytes");
        System.out.println("  signature length: " + fresh.signToken(new byte[20]).length);
    }
}
