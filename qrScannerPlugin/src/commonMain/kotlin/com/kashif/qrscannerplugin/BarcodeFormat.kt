package com.kashif.qrscannerplugin

import kotlin.jvm.JvmInline

/**
 * Open: new values can be added in a minor release, so a `when` over it needs an `else` branch.
 * A barcode symbology the scanner can be asked to read ([QRScannerConfig.formats]) and reports
 * ([Barcode.format]). A platform decoder that cannot read a requested format ignores it and logs
 * that once: iOS reads UPC-A codes as [EAN_13] and so ignores [UPC_A].
 */
@JvmInline
public value class BarcodeFormat private constructor(public val name: String) {
    override fun toString(): String = name

    public companion object {
        public val QR_CODE: BarcodeFormat = BarcodeFormat("QR_CODE")
        public val AZTEC: BarcodeFormat = BarcodeFormat("AZTEC")
        public val DATA_MATRIX: BarcodeFormat = BarcodeFormat("DATA_MATRIX")
        public val PDF_417: BarcodeFormat = BarcodeFormat("PDF_417")
        public val EAN_13: BarcodeFormat = BarcodeFormat("EAN_13")
        public val EAN_8: BarcodeFormat = BarcodeFormat("EAN_8")
        public val UPC_A: BarcodeFormat = BarcodeFormat("UPC_A")
        public val UPC_E: BarcodeFormat = BarcodeFormat("UPC_E")
        public val CODE_128: BarcodeFormat = BarcodeFormat("CODE_128")
        public val CODE_39: BarcodeFormat = BarcodeFormat("CODE_39")
        public val CODE_93: BarcodeFormat = BarcodeFormat("CODE_93")
        public val CODABAR: BarcodeFormat = BarcodeFormat("CODABAR")
        public val ITF: BarcodeFormat = BarcodeFormat("ITF")

        /** Every format of this release, in declaration order. */
        public val entries: List<BarcodeFormat> = listOf(
            QR_CODE,
            AZTEC,
            DATA_MATRIX,
            PDF_417,
            EAN_13,
            EAN_8,
            UPC_A,
            UPC_E,
            CODE_128,
            CODE_39,
            CODE_93,
            CODABAR,
            ITF,
        )
    }
}
