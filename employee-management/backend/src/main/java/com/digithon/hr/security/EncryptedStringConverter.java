package com.digithon.hr.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Encrypts an entity attribute on write and decrypts it on read. Apply with
 * {@code @Convert(converter = EncryptedStringConverter.class)} on encrypted PI columns
 * once they are wide enough for ciphertext.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final PiFieldCipher cipher;

    public EncryptedStringConverter(PiFieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
