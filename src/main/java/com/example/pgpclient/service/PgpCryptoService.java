package com.example.pgpclient.service;

import com.example.pgpclient.config.PgpProperties;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.operator.PublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.util.io.Streams;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Date;
import java.util.Iterator;

@Service
public class PgpCryptoService {

    private final PGPPublicKey serverEncryptionPublicKey;
    private final PGPPublicKeyRingCollection serverPublicKeyRings;
    private final PGPPrivateKey clientSigningPrivateKey;
    private final PGPSecretKey clientSigningSecretKey;
    private final PGPSecretKeyRingCollection clientSecretKeyRings;
    private final char[] keyPassphrase;

    public PgpCryptoService(PgpProperties properties) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        this.keyPassphrase = new char[0];
        this.serverPublicKeyRings = readPublicKeyRings(properties.serverPublicKeyPath());
        this.serverEncryptionPublicKey = readEncryptionPublicKey(serverPublicKeyRings);
        this.clientSecretKeyRings = readSecretKeyRings(properties.clientPrivateKeyPath());
        this.clientSigningSecretKey = findSigningSecretKey(clientSecretKeyRings);
        this.clientSigningPrivateKey = extractPrivateKey(clientSigningSecretKey, keyPassphrase);
    }

    public String encryptForServer(String plainText) {
        try {
            byte[] data = plainText.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            try (ArmoredOutputStream armoredOutput = new ArmoredOutputStream(output)) {
                PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
                        new JcePGPDataEncryptorBuilder(org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags.AES_256)
                                .setWithIntegrityPacket(true)
                                .setSecureRandom(new java.security.SecureRandom())
                                .setProvider("BC")
                );
                encryptedDataGenerator.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(serverEncryptionPublicKey).setProvider("BC"));

                try (OutputStreams streams = writeLiteralData(encryptedDataGenerator, armoredOutput, data)) {
                    streams.close();
                }
            }

            return output.toString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt payload", ex);
        }
    }

    public String decryptFromServer(String armoredEncryptedText) {
        try {
            byte[] encrypted = armoredEncryptedText.getBytes(StandardCharsets.UTF_8);
            try (InputStream decoder = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(new ByteArrayInputStream(encrypted))) {
                PGPObjectFactory objectFactory = new PGPObjectFactory(decoder, new JcaKeyFingerprintCalculator());
                Object object = objectFactory.nextObject();

                PGPEncryptedDataList encryptedDataList;
                if (object instanceof PGPEncryptedDataList list) {
                    encryptedDataList = list;
                } else {
                    encryptedDataList = (PGPEncryptedDataList) objectFactory.nextObject();
                }

                PGPPublicKeyEncryptedData encryptedData = findEncryptedDataForPrivateKey(encryptedDataList);
                PGPPrivateKey clientDecryptPrivateKey = findDecryptPrivateKey(encryptedData.getKeyID());
                PublicKeyDataDecryptorFactory decryptorFactory = new JcePublicKeyDataDecryptorFactoryBuilder()
                        .setProvider("BC")
                    .build(clientDecryptPrivateKey);

                try (InputStream clear = encryptedData.getDataStream(decryptorFactory)) {
                    PGPObjectFactory plainFactory = new PGPObjectFactory(clear, new JcaKeyFingerprintCalculator());
                    Object plainObject = plainFactory.nextObject();

                    if (plainObject instanceof PGPCompressedData compressedData) {
                        plainFactory = new PGPObjectFactory(compressedData.getDataStream(), new JcaKeyFingerprintCalculator());
                        plainObject = plainFactory.nextObject();
                    }

                    if (!(plainObject instanceof PGPLiteralData literalData)) {
                        throw new PGPException("Invalid PGP message format");
                    }

                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    Streams.pipeAll(literalData.getInputStream(), output);
                    return output.toString(StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt payload", ex);
        }
    }

    public String signPayload(String payload) {
        try {
            PGPSignatureGenerator signatureGenerator = new PGPSignatureGenerator(
                    new JcaPGPContentSignerBuilder(clientSigningSecretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
                            .setProvider("BC")
            );
            signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, clientSigningPrivateKey);
            signatureGenerator.update(payload.getBytes(StandardCharsets.UTF_8));

            PGPSignature signature = signatureGenerator.generate();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ArmoredOutputStream armoredOutput = new ArmoredOutputStream(output)) {
                signature.encode(armoredOutput);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign payload", ex);
        }
    }

    public void verifyServerSignature(String payload, String armoredSignature) {
        try {
            PGPSignature signature = readSignature(armoredSignature);
            PGPPublicKey signerKey = serverPublicKeyRings.getPublicKey(signature.getKeyID());
            if (signerKey == null) {
                throw new IllegalStateException("Signer key not found in server public keyring");
            }

            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), signerKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            if (!signature.verify()) {
                throw new IllegalStateException("Invalid server signature");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to verify server signature", ex);
        }
    }

    private OutputStreams writeLiteralData(PGPEncryptedDataGenerator encryptedDataGenerator,
                                           ArmoredOutputStream armoredOutput,
                                           byte[] data) throws IOException, PGPException {
        OutputStreams streams = new OutputStreams();
        streams.encryptedOut = encryptedDataGenerator.open(armoredOutput, new byte[4096]);

        PGPCompressedDataGenerator compressedDataGenerator = new PGPCompressedDataGenerator(PGPCompressedData.ZIP);
        streams.compressedOut = compressedDataGenerator.open(streams.encryptedOut);

        PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
        streams.literalOut = literalDataGenerator.open(
                streams.compressedOut,
                PGPLiteralData.BINARY,
                "payload.json",
                data.length,
                new Date()
        );
        streams.literalOut.write(data);
        streams.literalOut.close();
        streams.compressedOut.close();
        streams.encryptedOut.close();
        return streams;
    }

    private PGPPublicKeyEncryptedData findEncryptedDataForPrivateKey(PGPEncryptedDataList encryptedDataList) {
        Iterator<?> encryptedDataObjects = encryptedDataList.getEncryptedDataObjects();
        while (encryptedDataObjects.hasNext()) {
            PGPPublicKeyEncryptedData encryptedData = (PGPPublicKeyEncryptedData) encryptedDataObjects.next();
            if (hasSecretKey(encryptedData.getKeyID())) {
                return encryptedData;
            }
        }
        throw new IllegalStateException("No encrypted packet found for client keyring");
    }

    private PGPPublicKeyRingCollection readPublicKeyRings(String path) throws Exception {
        try (InputStream keyInput = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(new FileInputStream(path))) {
            return new PGPPublicKeyRingCollection(keyInput, new JcaKeyFingerprintCalculator());
        }
    }

    private PGPPublicKey readEncryptionPublicKey(PGPPublicKeyRingCollection keyRings) {
        for (var keyRing : keyRings) {
            Iterator<PGPPublicKey> keys = keyRing.getPublicKeys();
            while (keys.hasNext()) {
                PGPPublicKey key = keys.next();
                if (key.isEncryptionKey()) {
                    return key;
                }
            }
        }
        throw new IllegalStateException("Encryption public key not found in server keyring");
    }

    private PGPSecretKeyRingCollection readSecretKeyRings(String path) throws Exception {
        try (InputStream keyInput = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(new FileInputStream(path))) {
            return new PGPSecretKeyRingCollection(keyInput, new JcaKeyFingerprintCalculator());
        }
    }

    private PGPSecretKey findSigningSecretKey(PGPSecretKeyRingCollection keyRings) {
        for (var keyRing : keyRings) {
            Iterator<PGPSecretKey> keys = keyRing.getSecretKeys();
            while (keys.hasNext()) {
                PGPSecretKey key = keys.next();
                if (key.isSigningKey()) {
                    return key;
                }
            }
        }
        throw new IllegalStateException("Signing private key not found");
    }

    private boolean hasSecretKey(long keyId) {
        return clientSecretKeyRings.getSecretKey(keyId) != null;
    }

    private PGPPrivateKey findDecryptPrivateKey(long keyId) throws Exception {
        PGPSecretKey secretKey = clientSecretKeyRings.getSecretKey(keyId);
        if (secretKey == null) {
            throw new IllegalStateException("No private key found for key ID " + keyId);
        }
        return extractPrivateKey(secretKey, keyPassphrase);
    }

    private PGPPrivateKey extractPrivateKey(PGPSecretKey secretKey, char[] passphrase) throws Exception {
        return secretKey.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                .setProvider("BC")
                .build(passphrase));
    }

    private PGPSignature readSignature(String armoredSignature) throws Exception {
        try (InputStream signatureInput = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armoredSignature.getBytes(StandardCharsets.UTF_8)))) {
            PGPObjectFactory objectFactory = new PGPObjectFactory(signatureInput, new JcaKeyFingerprintCalculator());
            Object object = objectFactory.nextObject();

            if (object instanceof PGPSignatureList signatureList && !signatureList.isEmpty()) {
                return signatureList.get(0);
            }
            if (object instanceof PGPSignature signature) {
                return signature;
            }
        }
        throw new IllegalStateException("Invalid signature payload");
    }

    private static class OutputStreams implements AutoCloseable {
        private java.io.OutputStream encryptedOut;
        private java.io.OutputStream compressedOut;
        private java.io.OutputStream literalOut;

        @Override
        public void close() throws Exception {
            if (literalOut != null) {
                literalOut.close();
            }
            if (compressedOut != null) {
                compressedOut.close();
            }
            if (encryptedOut != null) {
                encryptedOut.close();
            }
        }
    }
}
