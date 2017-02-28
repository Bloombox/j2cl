package javaemul.internal;

import jsinterop.annotations.JsType;

/**
 * Defines utility static functions that map from transpiled Long instantiation and arithmetic
 * operations to some particular Long emulation library. (In this case Closure's goog.math.Long)
 */
@JsType(namespace = "vmbootstrap")
class LongUtils {
  // Make sure clinit is self nullified so tests doesn't time out (see b/28023199).
  static int PRESERVE_CLINIT_NULLIFICATION = 1;

  public static int $compare(NativeLong a, NativeLong b) {
    return a.compare(b);
  }

  public static NativeLong $fromInt(int value) {
    return NativeLong.fromInt(value);
  }

  public static NativeLong $fromNumber(double value) {
    return NativeLong.fromNumber(value);
  }

  public static int $toInt(NativeLong value) {
    return value.toInt();
  }

  public static double $toNumber(NativeLong value) {
    return value.toNumber();
  }

  public static NativeLong $and(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.and(rightLong);
  }

  public static NativeLong $not(NativeLong valueLong) {
    return valueLong.not();
  }

  public static NativeLong $divide(NativeLong leftLong, NativeLong rightLong) {
    LongUtils.checkDivisorZero(rightLong);
    return leftLong.div(rightLong);
  }

  public static boolean $equals(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.equals(rightLong);
  }

  public static boolean $greater(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.greaterThan(rightLong);
  }

  public static boolean $greaterEquals(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.greaterThanOrEqual(rightLong);
  }

  public static NativeLong $leftShift(NativeLong valueLong, NativeLong numBits) {
    return valueLong.shiftLeft(numBits.toInt());
  }

  public static boolean $less(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.lessThan(rightLong);
  }

  public static boolean $lessEquals(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.lessThanOrEqual(rightLong);
  }

  public static NativeLong $minus(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.subtract(rightLong);
  }

  public static NativeLong $negate(NativeLong valueLong) {
    return valueLong.negate();
  }

  public static boolean $notEquals(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.notEquals(rightLong);
  }

  public static NativeLong $or(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.or(rightLong);
  }

  public static NativeLong $plus(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.add(rightLong);
  }

  public static NativeLong $remainder(NativeLong leftLong, NativeLong rightLong) {
    LongUtils.checkDivisorZero(rightLong);
    return leftLong.modulo(rightLong);
  }

  public static NativeLong $rightShiftSigned(NativeLong valueLong, NativeLong numBits) {
    return valueLong.shiftRight(numBits.toInt());
  }

  public static NativeLong $rightShiftUnsigned(NativeLong valueLong, NativeLong numBits) {
    return valueLong.shiftRightUnsigned(numBits.toInt());
  }

  public static NativeLong $times(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.multiply(rightLong);
  }

  public static NativeLong $xor(NativeLong leftLong, NativeLong rightLong) {
    return leftLong.xor(rightLong);
  }

  public static int $getHighBits(NativeLong valueLong) {
    return valueLong.getHighBits();
  }

  public static int $getLowBits(NativeLong valueLong) {
    return valueLong.getLowBits();
  }

  public static String $toString(NativeLong valueLong) {
    return valueLong.toString();
  }

  public static void checkDivisorZero(NativeLong divisor) {
    InternalPreconditions.checkArithmetic(!divisor.isZero());
  }

  @JsType(isNative = true, name = "Long", namespace = "nativebootstrap")
  private static class NativeLong {
    public static native NativeLong fromBits(int lowBits, int highBits);

    public static native NativeLong fromInt(int value);

    public static native NativeLong fromNumber(double value);

    public native NativeLong add(NativeLong rightLong);

    public native NativeLong and(NativeLong rightLong);

    public native int compare(NativeLong b);

    public native NativeLong div(NativeLong rightLong);

    public native int getHighBits();

    public native int getLowBits();

    public native boolean greaterThan(NativeLong rightLong);

    public native boolean greaterThanOrEqual(NativeLong rightLong);

    public native boolean isZero();

    public native boolean lessThan(NativeLong rightLong);

    public native boolean lessThanOrEqual(NativeLong rightLong);

    public native NativeLong modulo(NativeLong rightLong);

    public native NativeLong multiply(NativeLong rightLong);

    public native NativeLong negate();

    public native NativeLong not();

    public native boolean notEquals(NativeLong rightLong);

    public native NativeLong or(NativeLong rightLong);

    public native NativeLong shiftLeft(int int1);

    public native NativeLong shiftRight(int int1);

    public native NativeLong shiftRightUnsigned(int int1);

    public native NativeLong subtract(NativeLong rightLong);

    public native int toInt();

    public native double toNumber();

    public native NativeLong xor(NativeLong rightLong);

    public native boolean equals(Object rightLong);
  }
}
