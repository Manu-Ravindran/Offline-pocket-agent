package com.offline.pocketagent.security;

import android.os.ParcelFileDescriptor;

/** Only read-only descriptors and bounded primitive results cross this boundary. */
interface IPdfSandbox {
    int pageCount(in ParcelFileDescriptor source);

    // metadata = [width, height, textWasTruncated, rawPixelBytes].
    // text has one element. A page has either text or raw ARGB_8888 pixels.
    ParcelFileDescriptor readPage(in ParcelFileDescriptor source, int pageIndex,
        int maxEdge, int maxTextChars, out int[] metadata, out String[] text);
}
