#include "stablear/vision_c.h"

int main(void) {
    stablear_xfeat_tracker* tracker = stablear_xfeat_create();
    if (!tracker) return 1;
    /* No frame/root exists, so staging must fail closed rather than manufacturing a token. */
    if (stablear_xfeat_stage_template(tracker, 1, 10.0, 10.0, 0, 0.0, 0.0, 0.0, 1.0, 1.0) != 0) return 2;
    if (stablear_xfeat_commit_staged_template(tracker, 1, 1) != 0) return 3;
    stablear_xfeat_discard_staged_template(tracker, 1, 1);
    stablear_xfeat_clear(tracker);
    stablear_xfeat_destroy(tracker);
    return 0;
}
