package nom.tam.fits.utilities;

/*
 * #%L
 * nom.tam FITS library
 * %%
 * Copyright (C) 1996 - 2015 nom-tam-fits
 * %%
 * This is free and unencumbered software released into the public domain.
 * 
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * #L%
 */

import static nom.tam.util.FitsIO.BITS_OF_1_BYTE;
import static nom.tam.util.FitsIO.BITS_OF_2_BYTES;
import static nom.tam.util.FitsIO.BITS_OF_3_BYTES;
import static nom.tam.util.FitsIO.BYTE_1_OF_LONG_MASK;
import static nom.tam.util.FitsIO.BYTE_2_OF_LONG_MASK;
import static nom.tam.util.FitsIO.BYTE_3_OF_LONG_MASK;
import static nom.tam.util.FitsIO.BYTE_4_OF_LONG_MASK;
import static nom.tam.util.FitsIO.HIGH_INTEGER_MASK;
import static nom.tam.util.FitsIO.INTEGER_MASK;
import static nom.tam.util.FitsIO.SHORT_OF_LONG_MASK;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCommentsMap;
import nom.tam.fits.header.Checksum;
import nom.tam.util.AsciiFuncs;
import nom.tam.util.BufferedDataOutputStream;

public final class FitsCheckSum {

    private static final int CHECKSUM_STRING_SIZE = 16;

    /**
     * logger to log to.
     */
    private static final Logger LOG = Logger.getLogger(FitsCheckSum.class.getName());

    private static final int CHECKSUM_BLOCK_SIZE = 4;

    private static final int CHECKSUM_HALF_BLOCK_SIZE = 2;

    private FitsCheckSum() {
    }

    /**
     * Calculate the Seaman-Pence 32-bit 1's complement checksum over the byte
     * stream. The option to start from an intermediate checksum accumulated
     * over another previous byte stream is not implemented. The implementation
     * accumulates in two 64-bit integer values the two low-order and the two
     * high-order bytes of adjacent 4-byte groups. A carry-over of bits is never
     * done within the main loop (only once at the end at reduction to a 32-bit
     * positive integer) since an overflow of a 64-bit value (signed, with
     * maximum at 2^63-1) by summation of 16-bit values could only occur after
     * adding approximately 140G short values (=2^47) (280GBytes) or more. We
     * assume for now that this routine here is never called to swallow FITS
     * files of that size or larger. by R J Mathar
     * 
     * @param data
     *            the byte sequence
     * @return the 32bit checksum in the range from 0 to 2^32-1
     * @see Checksum#CHECKSUM
     * @since 2005-10-05
     */
    public static long checksum(final byte[] data) {
        long hi = 0;
        long lo = 0;
        final int len = CHECKSUM_HALF_BLOCK_SIZE * (data.length / CHECKSUM_BLOCK_SIZE);
        // System.out.println(data.length + " bytes") ;
        final int remain = data.length % CHECKSUM_BLOCK_SIZE;
        if (remain != 0) {
            throw new IllegalArgumentException("fits blocks always must be devidable by 4");
        }
        /*
         * a write(2) on Sparc/PA-RISC would write the MSB first, on Linux the
         * LSB; by some kind of coincidence, we can stay with the byte order
         * known from the original C version of the algorithm.
         */
        for (int i = 0; i < len; i += CHECKSUM_HALF_BLOCK_SIZE) {
            /*
             * The four bytes in this block handled by a single 'i' are each
             * signed (-128 to 127) in Java and need to be masked indivdually to
             * avoid sign extension /propagation.
             */
            int offset = CHECKSUM_HALF_BLOCK_SIZE * i;
            hi += data[offset++] << BITS_OF_1_BYTE & BYTE_2_OF_LONG_MASK | data[offset++] & BYTE_1_OF_LONG_MASK;
            lo += data[offset++] << BITS_OF_1_BYTE & BYTE_2_OF_LONG_MASK | data[offset++] & BYTE_1_OF_LONG_MASK;
        }

        long hicarry = hi >>> BITS_OF_2_BYTES;
        long locarry = lo >>> BITS_OF_2_BYTES;
        while (hicarry != 0 || locarry != 0) {
            hi = (hi & SHORT_OF_LONG_MASK) | locarry;
            lo = (lo & SHORT_OF_LONG_MASK) | hicarry;
            hicarry = hi >>> BITS_OF_2_BYTES;
            locarry = lo >>> BITS_OF_2_BYTES;
        }
        return (hi << BITS_OF_2_BYTES) | lo;
    }

    /**
     * Encode a 32bit integer according to the Seaman-Pence proposal.
     * 
     * @see <a
     *      href="http://heasarc.gsfc.nasa.gov/docs/heasarc/ofwg/docs/general/checksum/node14.html#SECTION00035000000000000000">heasarc
     *      checksum doc</a>
     * @param c
     *            the checksum previously calculated
     * @param compl
     *            complement the value
     * @return the encoded string of 16 bytes.
     * @author R J Mathar
     * @since 2005-10-05
     */
    public static String checksumEnc(final long c, final boolean compl) {
        byte[] asc = new byte[CHECKSUM_STRING_SIZE];
        final int[] exclude = {
            0x3a,
            0x3b,
            0x3c,
            0x3d,
            0x3e,
            0x3f,
            0x40,
            0x5b,
            0x5c,
            0x5d,
            0x5e,
            0x5f,
            0x60
        };
        final long[] mask = {
            BYTE_4_OF_LONG_MASK,
            BYTE_3_OF_LONG_MASK,
            BYTE_2_OF_LONG_MASK,
            BYTE_1_OF_LONG_MASK
        };
        final int offset = 0x30; /* ASCII 0 (zero */
        final long value = compl ? ~c : c;
        for (int i = 0; i < CHECKSUM_BLOCK_SIZE; i++) {
            // each byte becomes four
            final int byt = (int) ((value & mask[i]) >>> BITS_OF_3_BYTES - BITS_OF_1_BYTE * i);
            final int quotient = byt / CHECKSUM_BLOCK_SIZE + offset;
            final int remainder = byt % CHECKSUM_BLOCK_SIZE;
            int[] ch = new int[CHECKSUM_BLOCK_SIZE];
            for (int j = 0; j < CHECKSUM_BLOCK_SIZE; j++) {
                ch[j] = quotient;
            }

            ch[0] += remainder;
            boolean check = true;
            while (check) {
                // avoid ASCII punctuation
                check = false;
                for (int element : exclude) {
                    for (int j = 0; j < CHECKSUM_BLOCK_SIZE; j += CHECKSUM_HALF_BLOCK_SIZE) {
                        if (ch[j] == element || ch[j + 1] == element) {
                            ch[j]++;
                            ch[j + 1]--;
                            check = true;
                        }
                    }
                }
            }

            for (int j = 0; j < CHECKSUM_BLOCK_SIZE; j++) {
                // assign the bytes
                asc[CHECKSUM_BLOCK_SIZE * j + i] = (byte) ch[j];
            }
        }
        // shift the bytes 1 to the right circularly.
        String resul = AsciiFuncs.asciiString(asc, CHECKSUM_STRING_SIZE - 1, 1);
        return resul.concat(AsciiFuncs.asciiString(asc, 0, CHECKSUM_STRING_SIZE - 1));
    }

    /**
     * Add or update the CHECKSUM keyword. by R J Mathar
     * 
     * @param hdu
     *            the HDU to be updated.
     * @throws FitsException
     *             if the operation failed
     * @since 2005-10-05
     */
    public static void setChecksum(BasicHDU<?> hdu) throws FitsException {
        /*
         * the next line with the delete is needed to avoid some unexpected
         * problems with non.tam.fits.Header.checkCard() which otherwise says it
         * expected PCOUNT and found DATE.
         */
        Header hdr = hdu.getHeader();
        hdr.deleteKey(Checksum.CHECKSUM.key());
        /*
         * jThis would need org.nevec.utils.DateUtils compiled before
         * org.nevec.prima.fits .... final String doneAt =
         * DateUtils.dateToISOstring(0) ; We need to save the value of the
         * comment string because this is becoming part of the checksum
         * calculated and needs to be re-inserted again - with the same string -
         * when the second/final call to addValue() is made below.
         */
        final String doneAt = HeaderCommentsMap.getComment("fits:checksum:1");
        hdr.addValue(Checksum.CHECKSUM.key(), "0000000000000000", doneAt);

        /*
         * Convert the entire sequence of 2880 byte header cards into a byte
         * array. The main benefit compared to the C implementations is that we
         * do not need to worry about the particular byte order on machines
         * (Linux/VAX/MIPS vs Hp-UX, Sparc...) supposed that the correct
         * implementation is in the write() interface.
         */
        ByteArrayOutputStream hduByteImage = new ByteArrayOutputStream();
        BufferedDataOutputStream bdos = new BufferedDataOutputStream(hduByteImage);

        // DATASUM keyword.
        hdu.getData().write(bdos);
        try {
            bdos.flush();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "should not happen", e);
        }
        byte[] data = hduByteImage.toByteArray();
        checksum(data);
        hdu.write(new BufferedDataOutputStream(hduByteImage));
        long csd = checksum(data);
        hdu.getHeader().addValue("DATASUM", "" + csd, "Checksum of data");

        // We already have the checsum of the data. Lets compute it for
        // the header.
        hduByteImage.reset();
        hdu.getHeader().write(bdos);
        try {
            bdos.flush();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "should not happen", e);
        }
        data = hduByteImage.toByteArray();

        long csh = checksum(data);

        long cshdu = csh + csd;
        // If we had a carry it should go into the
        // beginning.
        while ((cshdu & HIGH_INTEGER_MASK) != 0) {
            cshdu = (cshdu & INTEGER_MASK) + 1;
        }
        /*
         * This time we do not use a deleteKey() to ensure that the keyword is
         * replaced "in place". Note that the value of the checksum is actually
         * independent to a permutation of the 80-byte records within the
         * header.
         */
        hdr.addValue("CHECKSUM", checksumEnc(cshdu, true), doneAt);
    }
}