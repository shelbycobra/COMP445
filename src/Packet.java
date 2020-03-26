import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class Packet {

    public final static int HEADER_SIZE = 11;
    public final static int PACKET_SIZE = 1024;
    public final static int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;

    public final static int DATA = 1;
    public final static int SYN = 2;
    public final static int SYNACK = 3;
    public final static int ACK = 4;
    public final static int NAK = 5;

    private final int type;
    private final long sequenceNumber;
    private final InetAddress peerAddress;
    private final int peerPort;
    private final byte[] payload;

    public Packet(int type, long sequenceNumber, InetAddress peerAddress, int peerPort, byte[] payload) {
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.payload = payload;
    }

    public int getType() {
        return type;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public InetAddress getPeerAddress() {
        return peerAddress;
    }

    public int getPeerPort() {
        return peerPort;
    }

    public byte[] getPayload() {
        return payload;
    }

    public static Packet fromBuffer(byte[] bytes) throws UnknownHostException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        Builder builder = new Builder();

        // Build Packet
        builder.setType(Byte.toUnsignedInt(buf.get()));
        builder.setSequenceNumber(buf.getInt());

        byte[] host = new byte[]{buf.get(), buf.get(), buf.get(), buf.get()};
        builder.setPeerAddress(Inet4Address.getByAddress(host));
        builder.setPortNumber(Short.toUnsignedInt(buf.getShort()));

        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        builder.setPayload(payload);

        return builder.create();
    }

    public DatagramPacket toDatagramPacket(InetSocketAddress receiver) {
        // Get total size of packet
        int packetLength = this.payload.length + HEADER_SIZE;

        // Truncate packet length to fit into PACKET_SIZE
        if (packetLength > PACKET_SIZE)
            packetLength = PACKET_SIZE;

        ByteBuffer byteBuffer = ByteBuffer.allocate(packetLength);

        byteBuffer.put((byte)this.type);
        byteBuffer.putInt((int) this.sequenceNumber);
        byteBuffer.put(peerAddress.getAddress());
        byteBuffer.putShort((short) peerPort);
        byteBuffer.put(payload);

        byte[] tmp = byteBuffer.array();

        return new DatagramPacket(tmp, tmp.length, receiver.getAddress(), receiver.getPort());
    }

    @Override
    public String toString() {
        return "Type: " + this.type
            + "\nSequence Number: #" + sequenceNumber
            + "\nPeer: " + peerAddress + ":" + peerPort
            + "\nPayload:\n" + new String(payload);
    }

    public static class Builder {
        private int type;
        private long sequenceNumber;
        private InetAddress peerAddress;
        private int portNumber;
        private byte[] payload;

        public Builder setType(int type) {
            this.type = type;
            return this;
        }

        public Builder setSequenceNumber(long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder setPeerAddress(InetAddress peerAddress) {
            this.peerAddress = peerAddress;
            return this;
        }

        public Builder setPortNumber(int portNumber) {
            this.portNumber = portNumber;
            return this;
        }

        public Builder setPayload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        public Packet create() {
            return new Packet(type, sequenceNumber, peerAddress, portNumber, payload);
        }
    }
}