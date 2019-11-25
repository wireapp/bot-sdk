//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk.tools;

import com.wire.bots.sdk.exceptions.AuthException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigInteger;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {
    private static Pattern pattern = Pattern.compile("(?<=@)([a-zA-Z0-9\\_]{3,})");
    private static final String HMAC_SHA_1 = "HmacSHA1";

    public static byte[] encrypt(byte[] key, byte[] dataToSend, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
        c.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));
        byte[] bytes = c.doFinal(dataToSend);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(iv);
        os.write(bytes);
        return os.toByteArray();
    }

    public static byte[] decrypt(byte[] key, byte[] encrypted) throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(encrypted);
        byte[] iv = new byte[16];
        is.read(iv);
        byte[] bytes = toByteArray(is);
        IvParameterSpec vec = new IvParameterSpec(iv);
        SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
        cipher.init(Cipher.DECRYPT_MODE, skeySpec, vec);

        return cipher.doFinal(bytes);
    }

    public static SecretKey genKey(char[] password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password, salt, 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    public static String readLine(File file) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return br.readLine();
        }
    }

    public static String readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            return new String(data, "UTF-8");
        }
    }

    public static void writeLine(String line, File file) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            bw.write(line);
        }
    }

    public static String calcMd5(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(bytes, 0, bytes.length);
        byte[] hash = md.digest();
        byte[] byteArray = Base64.getEncoder().encode(hash);
        return new String(byteArray);
    }

    public static String digest(MessageDigest md, byte[] bytes) {
        md.update(bytes, 0, bytes.length);
        byte[] hash = md.digest();
        byte[] byteArray = Base64.getEncoder().encode(hash);
        return new String(byteArray);
    }

    public static String getHmacSHA1(String payload, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac hmac = Mac.getInstance(HMAC_SHA_1);
        hmac.init(new SecretKeySpec(secret.getBytes(Charset.forName("UTF-8")), HMAC_SHA_1));
        byte[] bytes = hmac.doFinal(payload.getBytes(Charset.forName("UTF-8")));
        return String.format("%040x", new BigInteger(1, bytes));
    }

    public static byte[] toByteArray(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int n;
            byte[] buffer = new byte[1024 * 4];
            while (-1 != (n = input.read(buffer))) {
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    public static boolean compareAuthorizations(String auth1, String auth2) {
        if (auth1 == null || auth2 == null)
            return false;
        String token1 = extractToken(auth1);
        String token2 = extractToken(auth2);
        return token1.equals(token2);
    }

    private static String extractToken(String auth) {
        String[] split = auth.split(" ");
        return split.length == 1 ? split[0] : split[1];
    }

    public static String extractMimeType(byte[] imageData) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(imageData)) {
            String contentType = URLConnection.guessContentTypeFromStream(input);
            return contentType != null ? contentType : "image/xyz";
        }
    }

    public static byte[] getResource(String name) throws IOException {
        ClassLoader classLoader = Util.class.getClassLoader();
        try (InputStream resourceAsStream = classLoader.getResourceAsStream(name)) {
            return toByteArray(resourceAsStream);
        }
    }

    public static int mentionLen(String txt) {
        Matcher matcher = pattern.matcher(txt);
        if (matcher.find()) {
            return matcher.group().length() + 1;
        }
        return 0;
    }

    public static int mentionStart(String txt) {
        Matcher matcher = pattern.matcher(txt);
        if (matcher.find()) {
            return matcher.start() - 1;
        }
        return 0;
    }

    public static UUID extractUserId(String token) throws AuthException {
        String[] pairs = token.split("\\.");
        for (String pair : pairs) {
            String[] vals = pair.split("=");
            if (vals.length == 2 && vals[0].equals("u"))
                return UUID.fromString(vals[1]);
        }
        throw new AuthException("Error extracting userId from token", 403);
    }
}
