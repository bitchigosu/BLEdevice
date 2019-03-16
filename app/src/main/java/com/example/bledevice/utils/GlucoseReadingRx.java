package com.example.bledevice.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.UUID;

public class GlucoseReadingRx extends BluetoothCHelper {

    private ByteBuffer data = null;

    private int sequence;
    private int year;
    private int month;
    private int day;
    private int hour;
    private int minute;
    private int second;
    private int offset;
    private float kgl;
    private float mol;
    private double mgdl;
    private long time;
    private int sampleType;
    private int sampleLocation;
    private String device;
    private boolean contextInfoFollows;

    public GlucoseReadingRx() {}

    public GlucoseReadingRx(byte[] packet) {
        this(packet, null);
    }

    public GlucoseReadingRx(byte[] packet, String device) {
        if (packet.length >= 14) {
            data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);

            int flags = data.get(0);
            final boolean timeOffsetPresent = (flags & 0x01) > 0;
            final boolean typeAndLocationPresent = (flags & 0x02) > 0;
            final boolean concentrationUnitKgL = (flags & 0x04) == 0;
            final boolean sensorStatusAnnunciationPresent = (flags & 0x08) > 0;
            contextInfoFollows = (flags & 0x10) > 0;

            sequence = data.getShort(1);
            year = data.getShort(3);
            month = data.get(5);
            day = data.get(6);
            hour = data.get(7);
            minute = data.get(8);
            second = data.get(9);

            int ptr = 10;
            if (timeOffsetPresent) {
                offset = data.getShort(ptr);
                ptr += 2;
            }

            if (concentrationUnitKgL) {
                kgl = getSfloat16(data.get(ptr), data.get(ptr + 1));
                mgdl = kgl * 100000;
            } else {
                mol = getSfloat16(data.get(ptr), data.get(ptr + 1));
                mgdl = mol * 1000 * 18.018018f;
            }
            ptr += 2;

            if (typeAndLocationPresent) {
                final int typeAndLocation = data.get(ptr);
                sampleLocation = (typeAndLocation & 0xF0) >> 4;
                sampleType = (typeAndLocation & 0x0F);
                ptr++;
            }

            if (sensorStatusAnnunciationPresent) {
                final int status = data.get(ptr);

            }

            final Calendar calendar = Calendar.getInstance();
            calendar.set(year, month - 1, day, hour, minute, second);
            time = calendar.getTimeInMillis();

            this.device = device;
        }
    }

    public String toString() {
        return "Glucose data: mg/dl: " + mgdl + "  mol/l: " + mol + "  kg/l: " + kgl
                + "  seq:" + sequence + " sampleType: " + sampleType + "  sampleLocation: " + sampleLocation + "  time: " + hour + ":" + minute + ":" + second
                + "  " + day + "-" + month + "-" + year + " timeoffset: " + offset + " timestamp: " + time + " from: " + device + (contextInfoFollows ? "  CONTEXT FOLLOWS" : "");
    }

    public long offsetMs() {
        return (offset * 60000);
    }

    public UUID getUuid() {
        data.rewind();
        final byte[] barr = new byte[data.remaining()];
        data.get(barr);
        return UUID.nameUUIDFromBytes(barr);
    }

    public double asKetone() {
        return mgdl / 10d;
    }

}
