package org.whispersystems.textsecure.crypto.protocol;

import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.whispersystems.textsecure.crypto.InvalidKeyException;
import org.whispersystems.textsecure.crypto.InvalidMessageException;
import org.whispersystems.textsecure.crypto.ecc.Curve;
import org.whispersystems.textsecure.crypto.ecc.ECPublicKey;
import org.whispersystems.textsecure.crypto.protocol.WhisperProtos.WhisperMessage;
import org.whispersystems.textsecure.util.Conversions;
import org.whispersystems.textsecure.util.Hex;
import org.whispersystems.textsecure.util.Util;

import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class WhisperMessageV2 implements CiphertextMessage {

  private static final int MAC_LENGTH = 8;

  private final ECPublicKey senderEphemeral;
  private final int         counter;
  private final int         previousCounter;
  private final byte[]      ciphertext;
  private final byte[]      serialized;

  public WhisperMessageV2(byte[] serialized) throws InvalidMessageException {
    try {
      byte[][] messageParts = Util.split(serialized, 1, serialized.length - 1 - MAC_LENGTH, MAC_LENGTH);
      byte     version      = messageParts[0][0];
      byte[]   message      = messageParts[1];
      byte[]   mac          = messageParts[2];

      if (Conversions.highBitsToInt(version) != CURRENT_VERSION) {
        throw new InvalidMessageException("Unknown version: " + Conversions.lowBitsToInt(version));
      }

      WhisperMessage whisperMessage = WhisperMessage.parseFrom(message);

      if (!whisperMessage.hasCiphertext() ||
          !whisperMessage.hasCounter() ||
          !whisperMessage.hasEphemeralKey())
      {
        throw new InvalidMessageException("Incomplete message.");
      }

      this.serialized      = serialized;
      this.senderEphemeral = Curve.decodePoint(whisperMessage.getEphemeralKey().toByteArray(), 0);
      this.counter         = whisperMessage.getCounter();
      this.previousCounter = whisperMessage.getPreviousCounter();
      this.ciphertext      = whisperMessage.getCiphertext().toByteArray();
    } catch (InvalidProtocolBufferException e) {
      throw new InvalidMessageException(e);
    } catch (InvalidKeyException e) {
      throw new InvalidMessageException(e);
    }
  }

  public WhisperMessageV2(SecretKeySpec macKey, ECPublicKey senderEphemeral,
                          int counter, int previousCounter, byte[] ciphertext)
  {
    byte[] version = {Conversions.intsToByteHighAndLow(CURRENT_VERSION, CURRENT_VERSION)};
    byte[] message = WhisperMessage.newBuilder()
                                   .setEphemeralKey(ByteString.copyFrom(senderEphemeral.serialize()))
                                   .setCounter(counter)
                                   .setPreviousCounter(previousCounter)
                                   .setCiphertext(ByteString.copyFrom(ciphertext))
                                   .build().toByteArray();
    byte[] mac     = getMac(macKey, Util.combine(version, message));

    this.serialized      = Util.combine(version, message, mac);
    this.senderEphemeral = senderEphemeral;
    this.counter         = counter;
    this.previousCounter = previousCounter;
    this.ciphertext      = ciphertext;
  }

  public ECPublicKey getSenderEphemeral()  {
    return senderEphemeral;
  }

  public int getCounter() {
    return counter;
  }

  public byte[] getBody() {
    return ciphertext;
  }

  public void verifyMac(SecretKeySpec macKey)
      throws InvalidMessageException
  {
    byte[][] parts    = Util.split(serialized, serialized.length - MAC_LENGTH, MAC_LENGTH);
    byte[]   ourMac   = getMac(macKey, parts[0]);
    byte[]   theirMac = parts[1];

    if (!Arrays.equals(ourMac, theirMac)) {
      throw new InvalidMessageException("Bad Mac!");
    }
  }

  private byte[] getMac(SecretKeySpec macKey, byte[] serialized) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(macKey);

      byte[] fullMac = mac.doFinal(serialized);
      return Util.trim(fullMac, MAC_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (java.security.InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public byte[] serialize() {
    return serialized;
  }

  @Override
  public int getType() {
    return CiphertextMessage.CURRENT_WHISPER_TYPE;
  }

}
