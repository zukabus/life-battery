/* Golden-vector generator: emits reference HiSCoA output for synthetic bands.
 * Links captdriver's own hiscoa-compress.c so output is authoritative. */
#include "../src/std.h"
#include "../src/hiscoa-common.h"
#include "../src/hiscoa-compress.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned rnd_state = 12345;
static unsigned rnd(void) {
	rnd_state = rnd_state * 1103515245u + 12345u;
	return (rnd_state >> 16) & 0x7FFF;
}

static void emit(const char *name, const uint8_t *band,
                 unsigned line_size, unsigned nlines, int eob)
{
	size_t compsize = 2 * line_size * nlines + 4096;
	uint8_t *out = calloc(1, compsize);
	size_t n = hiscoa_compress_band(out, compsize, band, line_size, nlines,
	                                (enum hiscoa_eob_type) eob,
	                                &hiscoa_default_params);
	printf("%s %u %u %d %zu ", name, line_size, nlines, eob, n);
	for (size_t i = 0; i < n; ++i)
		printf("%02x", out[i]);
	printf("\n");
	free(out);
}

int main(void)
{
	unsigned line_size = 16, nlines = 8;
	unsigned sz = line_size * nlines;
	uint8_t *b = calloc(1, sz);

	/* 1. all zero */
	memset(b, 0x00, sz);
	emit("all_zero", b, line_size, nlines, 0);

	/* 2. all 0xFF */
	memset(b, 0xFF, sz);
	emit("all_ff", b, line_size, nlines, 0);
	emit("all_ff_lasteob", b, line_size, nlines, 1);

	/* 3. identical lines (exercises line-delta origins) */
	for (unsigned l = 0; l < nlines; ++l)
		for (unsigned i = 0; i < line_size; ++i)
			b[l * line_size + i] = (uint8_t) (i * 7 + 3);
	emit("repeat_lines", b, line_size, nlines, 0);

	/* 4. incrementing bytes, no repeats */
	for (unsigned i = 0; i < sz; ++i)
		b[i] = (uint8_t) i;
	emit("incrementing", b, line_size, nlines, 0);

	/* 5. pseudo-random */
	for (unsigned i = 0; i < sz; ++i)
		b[i] = (uint8_t) rnd();
	emit("random", b, line_size, nlines, 0);

	/* 6. sparse text-like: mostly zero, occasional glyph runs */
	memset(b, 0, sz);
	for (unsigned l = 1; l < nlines; l += 2)
		for (unsigned i = 3; i < 9; ++i)
			b[l * line_size + i] = 0x3C;
	emit("sparse_text", b, line_size, nlines, 0);

	/* 7. long run > 127 to exercise the 0xFC long-length escape */
	free(b);
	line_size = 64; nlines = 16; sz = line_size * nlines;
	b = calloc(1, sz);
	memset(b, 0xA5, sz);
	emit("long_run", b, line_size, nlines, 0);

	/* 8. realistic A4 line width, single line */
	free(b);
	line_size = 595; nlines = 1; sz = line_size * nlines;
	b = calloc(1, sz);
	for (unsigned i = 0; i < sz; ++i)
		b[i] = (i % 37 == 0) ? 0xFF : 0x00;
	emit("a4_single_line", b, line_size, nlines, 0);

	/* 9. realistic A4 band of 70 lines */
	free(b);
	line_size = 595; nlines = 70; sz = line_size * nlines;
	b = calloc(1, sz);
	memset(b, 0, sz);
	for (unsigned l = 10; l < 60; ++l)
		for (unsigned i = 50; i < 300; ++i)
			b[l * line_size + i] = (uint8_t) ((l * 31 + i * 17) & 0xFF);
	emit("a4_band70", b, line_size, nlines, 0);

	/* 10. single byte */
	free(b);
	b = calloc(1, 1);
	b[0] = 0x5A;
	emit("single_byte", b, 1, 1, 0);

	/* 11. empty-ish: one zero byte */
	b[0] = 0x00;
	emit("single_zero", b, 1, 1, 1);

	free(b);
	return 0;
}
