// DuelCraft native helpers next to ocgcore: BC1/BC3/BC7 decoding for Master Duel's card art (bcdec, MIT).
#define BCDEC_IMPLEMENTATION
#include "bcdec.h"
#include <cstdint>

// format: 10 = DXT1/BC1, 12 = DXT5/BC3, 25 = BC7 (Unity TextureFormat ids)
extern "C" __declspec(dllexport) int dc_decode_bc(int format, const uint8_t* src, int srcLen, int width, int height, uint8_t* dstRgba) {
	const int bw = (width + 3) / 4, bh = (height + 3) / 4;
	const int blockSize = format == 10 ? BCDEC_BC1_BLOCK_SIZE : format == 12 ? BCDEC_BC3_BLOCK_SIZE : BCDEC_BC7_BLOCK_SIZE;
	if (srcLen < bw * bh * blockSize) return -1;
	uint8_t block[4 * 4 * 4];
	for (int by = 0; by < bh; by++) {
		for (int bx = 0; bx < bw; bx++) {
			if (format == 10) bcdec_bc1(src, block, 4 * 4);
			else if (format == 12) bcdec_bc3(src, block, 4 * 4);
			else bcdec_bc7(src, block, 4 * 4);
			src += blockSize;
			for (int y = 0; y < 4; y++) {
				int py = by * 4 + y;
				if (py >= height) break;
				for (int x = 0; x < 4; x++) {
					int px = bx * 4 + x;
					if (px >= width) break;
					const uint8_t* s = block + (y * 4 + x) * 4;
					uint8_t* d = dstRgba + (py * width + px) * 4;
					d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = s[3];
				}
			}
		}
	}
	return 0;
}
