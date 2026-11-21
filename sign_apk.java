import com.android.apksig.ApkSigner;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public class sign_apk {
    public static void main(String[] args) throws Exception {
        // args: <unsigned.apk> <out.apk> <keystore.jks> <storepass> <alias> <keypass>
        File input = new File(args[0]);
        File output = new File(args[1]);
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = new FileInputStream(args[2])) {
            ks.load(in, args[3].toCharArray());
        }
        String alias = args[4];
        PrivateKey key = (PrivateKey) ks.getKey(alias, args[5].toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (java.security.cert.Certificate c : ks.getCertificateChain(alias)) {
            certs.add((X509Certificate) c);
        }
        ApkSigner.SignerConfig signerConfig =
            new ApkSigner.SignerConfig.Builder(alias, key, certs).build();

        new ApkSigner.Builder(Collections.singletonList(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(21)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign();
        System.out.println("SIGNED v1+v2+v3: " + output);
    }
}
