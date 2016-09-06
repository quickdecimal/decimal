package org.maxtomin.decimal;

import java.math.RoundingMode;

public class BaseDecimal {
    public static final String OVERFLOW_EXCEPTION = "Overflow";

    static final int[] POW10 = {
            1,
            10,
            100,
            1000,
            10000,
            100000,
            1000000,
            10000000,
            100000000,
            1000000000,
    };
    static final long[] LONG_POW10 = {
            1,
            10,
            100,
            1000,
            10000,
            100000,
            1000000,
            10000000,
            100000000,
            1000000000,
            10000000000L,
            100000000000L,
            1000000000000L,
            10000000000000L,
            100000000000000L,
            1000000000000000L,
            10000000000000000L,
            100000000000000000L,
            1000000000000000000L,
    };
    static final long[] SCALE_OVERFLOW_LIMITS = {
            Long.MAX_VALUE,
            Long.MAX_VALUE / 10,
            Long.MAX_VALUE / 100,
            Long.MAX_VALUE / 1000,
            Long.MAX_VALUE / 10000,
            Long.MAX_VALUE / 100000,
            Long.MAX_VALUE / 1000000,
            Long.MAX_VALUE / 10000000,
            Long.MAX_VALUE / 100000000,
            Long.MAX_VALUE / 1000000000,
    };
    static final char[][] ZEROES = {
            "".toCharArray(),
            "0".toCharArray(),
            "00".toCharArray(),
            "000".toCharArray(),
            "0000".toCharArray(),
            "00000".toCharArray(),
            "000000".toCharArray(),
            "0000000".toCharArray(),
            "00000000".toCharArray(),
            "000000000".toCharArray(),
            "0000000000".toCharArray(),
            "00000000000".toCharArray(),
            "000000000000".toCharArray(),
            "0000000000000".toCharArray(),
            "00000000000000".toCharArray(),
            "000000000000000".toCharArray(),
            "0000000000000000".toCharArray(),
            "00000000000000000".toCharArray(),
            "000000000000000000".toCharArray(),
            "0000000000000000000".toCharArray()
    };

    static final int WORD_BITS = 32;
    static final long WORD_CARRY = 1L << WORD_BITS;
    static final long WORD_LO_MASK = WORD_CARRY - 1;

    long a; // accumulator

    long mulhi_63_32(long a_63, long b_32) {
        long hi_31 = hi_32(a_63);

        long rLo_64 = lo_32(a_63) * b_32;
        long rHi_63 = hi_31 * b_32 + hi_32(rLo_64);

        a = lo_32(rLo_64);
        return rHi_63;
    }

    long scaleDivRound(long v, int s, long d, RoundingMode roundingMode) {
        if (v == AbstractDecimal.NaN || d == AbstractDecimal.NaN || d == 0) {
            return AbstractDecimal.NaN;
        }

        long sign1 = v >> 63;
        long sign2 = d >> 63;

        v = negIf(v, sign1); // ~v - 1 if negative
        d = negIf(d, sign2);

        long result = scalediv_63o_63(v, s, d);
        if (result == AbstractDecimal.NaN) {
            return result;
        }

        sign1 ^= sign2;

        return round(negIf(result, sign1), negIf(a, sign1), d, roundingMode);
    }

    long scalediv_63o_63(long v_63, int scale, long d_63) {
        long offset_63 = 0;
        if (v_63 >= d_63) {
            // v * m / d = (v / d * d + v % d) * m / d = v / d * m + v % d * m / d
            offset_63 = v_63 / d_63;
            v_63 = v_63 % d_63;

            if (offset_63 > SCALE_OVERFLOW_LIMITS[scale]) {
                return AbstractDecimal.NaN; // overflow
            }
            offset_63 *= POW10[scale]; // overflow possible here
        }
        assert v_63 < d_63 : "and therefore quotient < m_31 <= Integer.MAX_VALUE";

        long p_63 = mulhi_63_32(v_63, POW10[scale]);
        long p_32 = a;

        if (d_63 <= Integer.MAX_VALUE) {
            assert v_63 <= Integer.MAX_VALUE;
            v_63 *= POW10[scale]; // no overflow

            // simple division
            offset_63 += v_63 / d_63; // quotient (considering the offset)
            if (offset_63 < 0) {
                return AbstractDecimal.NaN; // overflow
            }
            a = v_63 % d_63; // remainder
            return offset_63;
        }

        // normalizing:
        int shift = Long.numberOfLeadingZeros(d_63) - 1; // don't touch the sign bit
        assert shift < 32;
        d_63 <<= shift;
        assert Long.numberOfLeadingZeros(p_63) >= shift + 1 : "otherwise quotient would be > Integer.MAX_VALUE";
        p_63 <<= shift;
        p_32 <<= shift;
        p_63 |= hi_32(p_32);
        p_32 = lo_32(p_32);

        // approximate division
        long d_31 = hi_32(d_63);
        long d_32 = lo_32(d_63);
        long qhat_32 = p_63 / d_31; // approximate division (high parts)
        qhat_32 = lo_32(qhat_32); // exact quotient can not be bigger than Integer.MAX_VALUE

        // multiply back
        long phat_63 = mulhi_63_32(d_63, qhat_32);
        long phat_32 = a;

        // our divisor is between 2^62 and 2^63 - 1 after normalization
        // we decreased dividend by max 2^32 - 1
        // so quotient is decreased by max 2^32 / 2^62 < 1

        // we decreased divisor by max 2^32 - 1 or by max fraction 2^32 / 2^62
        // so quotient is increased by max fraction 2^32 / 2^62 or by max absolute 2^32 / 2^62 * 2^31 = 2^63 / 2^62 = 2

        // phat must be < p, applying corrections (no more than 2)
        int counter = 0;
        while (greaterThan(phat_63, phat_32, p_63, p_32)) {
            assert ++counter <= 2;
            --qhat_32;

            // delta -= d
            phat_63 -= d_31;
            phat_32 -= d_32;
            if (phat_32 < 0) {
                --phat_63;
                phat_32 += WORD_CARRY;
            }
        }
        long remainder = ((p_63 - phat_63) << WORD_BITS) + p_32 - phat_32; // remainder
        if (remainder >= d_63 || remainder < 0) { // unsigned compare
            remainder -= d_63;
            qhat_32++;
        }
        assert remainder >= 0 && remainder < d_63 : "no more than 1 correction up";

        a = remainder >> shift;
        offset_63 += qhat_32;
        if (offset_63 < 0) {
            throw new ArithmeticException(OVERFLOW_EXCEPTION);
        }
        return offset_63;
    }

    long mulScaleRound(long a, long b, int scale, RoundingMode roundingMode) {
        if (a == AbstractDecimal.NaN || b == AbstractDecimal.NaN) {
            return AbstractDecimal.NaN;
        }

        long sign1 = a >> 63;
        long sign2 = b >> 63;

        a = negIf(a, sign1);
        b = negIf(b, sign2);

        long result = mulscale_63o_31(a, b, scale);
        if (result == AbstractDecimal.NaN) {
            return result;
        }

        sign1 ^= sign2;

        return round(negIf(result, sign1), negIf(this.a, sign1), LONG_POW10[scale], roundingMode);
    }

    long mulscale_63o_31(long a_63, long b_63, int scale) {
        // long multiplication
        long a_31 = hi_32(a_63);
        long a_32 = lo_32(a_63);
        long b_31 = hi_32(b_63);
        long b_32 = lo_32(b_63);

        long lo_64 = a_32 * b_32;
        // no unsigned overflow: (2^31 - 1) * (2^32 - 1) * 2 + 2^32 = 18446744065119617026 < 2^64 =
        //                                                          = 18446744073709551616
        // may be signed overflow
        long p_64 = a_32 * b_31 + a_31 * b_32 + hi_32(lo_64);
        long p_63 = a_31 * b_31;

        long p_32;
        long r_33 = -1; // remainder from division by 10^10 (if necessary)
        if (scale > 9) {
            // move everything to 4 words p[63][64]
            p_63 += hi_32(p_64);
            p_64 = (p_64 << WORD_BITS) | lo_32(lo_64);

            // now we need to long-divide by 10^10 first (and then - by 10^scale as usual)
            // the formula for final remainder derived from:
            // v / d1 / d2 = (q1 + r1 / d1) / d2 = q1 / d2 + r1 / d1d2 = q2 + r2 / d2 + r1 / d1d2 = q2 + (r2d1 + r1) / d1d2

            // p >>>= 3 [note: lowest 3 bits are still saved in lo_64]
            p_64 = (p_64 >>> 3) | (p_63 << 61);
            p_63 >>>= 3;

            // (p >>> 3) / (10^10 >>> 3) [note: this is NOT approximate, we just thrown away 3 zeros in denominator]
            long q1_63 = p_63 / 1250000000;
            r_33 = p_63 % 1250000000;

            p_63 = (r_33 << WORD_BITS) | hi_32(p_64);
            long q2_32 = p_63 / 1250000000;
            r_33 = p_63 % 1250000000;

            p_63 = (r_33 << WORD_BITS) | lo_32(p_64);
            long q3_32 = p_63 / 1250000000;
            r_33 = p_63 % 1250000000;
            // restoring 3 lowest bits of the remainder:
            r_33 = (r_33 << 3) + (lo_64 & 0x7);

            assert q1_63 >= 0 && q1_63 <= Integer.MAX_VALUE : "we have just divided it by 10^10 > 2^32";
            p_63 = (q1_63 << WORD_BITS) | q2_32;
            p_32 = q3_32;

            scale -= 10;
        } else {
            if (p_63 < 0 || p_63 > Integer.MAX_VALUE) {
                return AbstractDecimal.NaN; // overflow
            }

            // move everything to 3 words: p[63][32]
            p_63 <<= WORD_BITS; // no high word in p_63
            p_63 += p_64;
            p_32 = lo_32(lo_64);
        }

        // long division: words 2,1,0 by int POW10[scale]
        long result_63o = downScale_63_31(p_63, scale);
        long ql_32 = downScale_63_31((a << WORD_BITS) | p_32, scale);

        if (result_63o < 0 || result_63o > Integer.MAX_VALUE) {
            return AbstractDecimal.NaN; // overflow
        }

        if (r_33 != -1) {
            a = a * 10000000000L + r_33; // need to consider remainder of first division by 10^10
        }
        return (result_63o << WORD_BITS) | ql_32;
    }

    long downScale_63_31(long v_63, int scale) {
        // switch + division by constant is faster than "v_63 / PO10[scale]"
        // also good for signed numbers (remainder is negative for negative result)
        switch (scale) {
            case 0:
                a = 0;
                return v_63;
            case 1:
                a = v_63 % 10;
                return v_63 / 10;
            case 2:
                a = v_63 % 100;
                return v_63 / 100;
            case 3:
                a = v_63 % 1000;
                return v_63 / 1000;
            case 4:
                a = v_63 % 10000;
                return v_63 / 10000;
            case 5:
                a = v_63 % 100000;
                return v_63 / 100000;
            case 6:
                a = v_63 % 1000000;
                return v_63 / 1000000;
            case 7:
                a = v_63 % 10000000;
                return v_63 / 10000000;
            case 8:
                a = v_63 % 100000000;
                return v_63 / 100000000;
            case 9:
                a = v_63 % 1000000000;
                return v_63 / 1000000000;
            default:
                throw new IllegalArgumentException("Incorrect scale: " + scale);
        }
    }

    long unsignedDownScale_64_31(long v_64, int scale) {
        // supports unsigned values
        a = v_64 & 0x1;
        v_64 >>>= 1;
        switch (scale) {
            case 1:
                a = (v_64 % 5);
                return v_64 / 5;
            case 2:
                a |= (v_64 % 50) << 1;
                return v_64 / 50;
            case 3:
                a |= (v_64 % 500) << 1;
                return v_64 / 500;
            case 4:
                a |= (v_64 % 5000) << 1;
                return v_64 / 5000;
            case 5:
                a |= (v_64 % 50000) << 1;
                return v_64 / 50000;
            case 6:
                a |= (v_64 % 500000) << 1;
                return v_64 / 500000;
            case 7:
                a |= (v_64 % 5000000) << 1;
                return v_64 / 5000000;
            case 8:
                a |= (v_64 % 50000000) << 1;
                return v_64 / 50000000;
            case 9:
                a |= (v_64 % 500000000) << 1;
                return v_64 / 500000000;
            default:
                throw new IllegalArgumentException("Incorrect scale: " + scale);
        }
    }


    /**
     * Round common (and mixed) fractions.
     * Ca not take NaN, but can produce NaN (e.g. failed UNNECESSARY or rounding up +-MAX_VALUE)
     *
     * @param whole can be positive or negative
     * @param denominator must be positive.
     * @param numerator (unless it's 0) must have the sign of the whole
     * @param roundingMode all modes supported, NaN if UNNECESSARY check fails
     */
    protected static long round(long whole, long numerator, long denominator, RoundingMode roundingMode) {
        switch (roundingMode) {
            case UNNECESSARY: // 7
                return numerator == 0 ? whole : AbstractDecimal.NaN;
            case HALF_EVEN: // 6
                denominator -= whole & 0x1; // HALF_UP for odd, making denominator < numerator * 2, else HALF_DOWN
                // fall through
            case HALF_DOWN: // 5
                denominator /= 2;
                return numerator <= denominator && numerator >= -denominator ? whole :
                    whole + Long.signum(numerator);
            case HALF_UP: // 4
                denominator /= 2;
                return numerator < denominator && numerator > -denominator ? whole :
                    whole + Long.signum(numerator);
            case FLOOR: // 3
                return whole + (numerator >> 63); // decrement if negative
            case CEILING: // 2
                return whole - (-numerator >> 63); // increment if positive
            case DOWN: // 1
                return whole;
            case UP: // 0
                return whole + Long.signum(numerator);
            default:
                throw new IllegalArgumentException("Unknown rounding mode: " + roundingMode);
        }
    }

    private static boolean greaterThan(long ah_63, long al_32, long bh_63, long bl_32) {
        return ah_63 > bh_63 || ah_63 == bh_63 && al_32 >= bl_32;
    }

    private static long hi_32(long v_64) {
        return v_64 >>> WORD_BITS;
    }

    private static long lo_32(long v_64) {
        return v_64 & WORD_LO_MASK;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BaseDecimal that = (BaseDecimal) o;

        return a == that.a;
    }

    @Override
    public int hashCode() {
        return (int) (a ^ (a >>> 32));
    }

    public static void main(String[] args) {
        System.out.println(Long.toHexString(Integer.MAX_VALUE / 2 + 1));
        System.out.println(Long.toHexString(Long.MAX_VALUE));
        System.out.println(Long.toHexString((Long.MAX_VALUE - 1) / (Integer.MAX_VALUE / 2 + 1)));
    }

    static long negIf(long v, long sign) {
        return (v ^ sign) - sign; // if sign == -1, then return ~v + 1 == -v
    }
}

