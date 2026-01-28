package org.mtgprod;

/*
* u=230.2 -IEEE 754-> 0x43663333
i=16.35 -IEEE 754-> 0x4182cccd
f=49.98 -IEEE 754-> 0x4247eb85
k=0.92 -IEEE 754-> 0x3f6b851f
p=3492.6684 -IEEE 754-> 0x455a4ab2
q=1475.09 -IEEE 754-> 0x44b862e1
s=3763.77 -IEEE 754-> 0x456b3c52
lat=44.8 -IEEE 754-> 0x42333333
long=0 -IEEE 754-> 0x00000000
w=372.81 -IEEE 754-> 0x43ba67ae
prix=0,1765 -IEEE 754-> 0x3e34bc6a
* */

public class LoraPayload {
    byte[] rawData;

    public LoraPayload(byte[] rawData) {
        this.rawData = rawData;
    }
}
