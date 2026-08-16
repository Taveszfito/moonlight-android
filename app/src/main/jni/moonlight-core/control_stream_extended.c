// Android-client-only extension layer for Apollo Extended packets.
// Keep moonlight-common-c pristine: this translation unit includes its normal
// control-stream implementation and adds the one client-specific sender that
// microphone passthrough needs.
#include "moonlight-common-c/src/ControlStream.c"

int LiSendRawControlStreamPacket(uint16_t packetType, const void* data, int length) {
    if (AppVersionQuad[0] < 5 || !encryptedControlStream || data == NULL ||
            length <= 0 || length > 252) {
        return -1;
    }
    return sendMessageAndForget((short)packetType, (short)length, data,
            CTRL_CHANNEL_GENERIC, 0, false) != 0 ? 0 : -1;
}
