package com.mhamzah.gateway.mapping;

/** Where a mapping rule writes its value. */
public enum TargetType {
    BODY,
    HEADER,
    QUERY,
    PATH,
    /** The HTTP status of the client response (FLOW_RESPONSE rules only); target_path is not used. */
    STATUS,
    /**
     * The client response is the file a step downloaded (FLOW_RESPONSE rules only): {@code source_path =
     * $.steps.<name>} of a step with {@code body_codec = fileCodec}; target_path is not used. The file goes out with its
     * Content-Type and Content-Disposition; BODY rules and the flow's response_handler then do not apply. When the step
     * has no file (it answered JSON), the response is built as usual.
     */
    FILE,
    /**
     * Pass-through: copies the field at {@code source_path} unchanged to the same place in the output body. The source
     * is under {@code $.request.body} or {@code $.steps.<name>.body}; the target is the rest of that path, e.g.
     * {@code $.steps.account.body.detail.openDate} goes to {@code $.detail.openDate}. Copies are written before the
     * other rules. A field that another rule of the same list reads is remapped, not copied: a copy whose source
     * (or a parent of it) is another rule's source is skipped, and a copied object leaves out the fields other rules
     * read. See {@link CopyRules}.
     */
    COPY
}
