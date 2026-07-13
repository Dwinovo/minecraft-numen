package com.dwinovo.numen.security;

import com.dwinovo.numen.Constants;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** Per-user local encryption boundary for persisted API keys. */
public interface SecretProtector {
    String protect(String plaintext) throws Exception;
    String unprotect(String ciphertext) throws Exception;

    static SecretProtector forConfigDirectory(Path directory) {
        // Java 17 has no supported DPAPI API. Keep the platform boundary here so a native
        // DPAPI implementation can replace this fallback without touching config business logic.
        Constants.LOG.warn("[numen-security] DPAPI provider unavailable; using a per-user AES-GCM key file. "
                + "Protect the Numen config directory with your Windows account permissions.");
        return new AesGcm(directory.resolve("secret.key"));
    }

    final class AesGcm implements SecretProtector {
        private final Path keyFile;
        private final SecureRandom random = new SecureRandom();
        AesGcm(Path keyFile) { this.keyFile = keyFile; }

        public String protect(String plaintext) throws Exception {
            if (plaintext == null || plaintext.isBlank()) return "";
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return "aesgcm:v1:" + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        public String unprotect(String ciphertext) throws Exception {
            if (ciphertext == null || ciphertext.isBlank()) return "";
            String[] parts = ciphertext.split(":", 4);
            if (parts.length != 4 || !"aesgcm".equals(parts[0]) || !"v1".equals(parts[1]))
                throw new IllegalArgumentException("unsupported protected secret format");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[2])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])), java.nio.charset.StandardCharsets.UTF_8);
        }

        private SecretKey key() throws Exception {
            Files.createDirectories(keyFile.getParent());
            if (!Files.isRegularFile(keyFile)) {
                KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
                byte[] encoded = generator.generateKey().getEncoded();
                Files.write(keyFile, encoded, java.nio.file.StandardOpenOption.CREATE_NEW);
                try {
                    var view = Files.getFileAttributeView(keyFile, java.nio.file.attribute.AclFileAttributeView.class);
                    if (view != null) {
                        var owner = Files.getOwner(keyFile);
                        view.setAcl(java.util.List.of(java.nio.file.attribute.AclEntry.newBuilder()
                                .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                                .setPermissions(java.nio.file.attribute.AclEntryPermission.values()).build()));
                    }
                } catch (Exception error) { Constants.LOG.warn("[numen-security] couldn't tighten secret key ACL", error); }
            }
            return new SecretKeySpec(Files.readAllBytes(keyFile), "AES");
        }
    }
}
