package com.prasad.labelstudio;

/** One physical label to print. */
final class LabelJob {

    enum BarcodeMode { AUTO, MANUAL, NONE }

    String style = "";
    String model = "";
    String category = "";
    String colour = "";
    String size = "";

    BarcodeMode mode = BarcodeMode.NONE;

    /** The number/text that goes on the label. Filled in at print time for AUTO. */
    String barcode = "";

    /** True for reprints: the barcode is given and the counter is not advanced. */
    boolean fixed = false;

    /**
     * When > 0 this job reprints the uncertain label with that pending number;
     * a successful print consumes the pending number.
     */
    int resolvesPending = 0;

    /** Design and print options for the renderer (an org.json.JSONObject on Android). */
    Object context;
}
