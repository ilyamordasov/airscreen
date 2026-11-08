#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

#include "pairing.h"

int main(void)
{
    int result = 0;
    /* Empty key path keeps this test in memory; the app uses its private file. */
    pairing_t *pairing = pairing_init_generate("02:00:00:00:00:01", "", &result);
    assert(pairing);
    pairing_session_t *session = pairing_session_init(pairing);
    assert(session);
    unsigned char public_key[SRP_PK_SIZE] = {1};
    unsigned char proof[SRP_M2_SIZE] = {0};
    unsigned char epk[ED25519_KEY_SIZE] = {0};
    unsigned char tag[GCM_AUTHTAG_SIZE] = {0};

    /* Each formerly dereferenced session->srp before checking the stage. */
    assert(srp_validate_proof(session, pairing, public_key, sizeof(public_key),
                              proof, 20, sizeof(proof)) < 0);
    assert(srp_confirm_pair_setup(session, pairing, epk, tag) < 0);
    assert(srp_validate_proof(NULL, pairing, public_key, sizeof(public_key),
                              proof, 20, sizeof(proof)) < 0);
    assert(srp_confirm_pair_setup(NULL, pairing, epk, tag) < 0);

    const char *salt = NULL, *server_key = NULL;
    int salt_len = 0, key_len = 0;
    assert(srp_new_user(session, pairing, NULL, "1234", &salt, &salt_len,
                        &server_key, &key_len) < 0);
    assert(srp_new_user(session, pairing, "02:00:00:00:00:02", "1234", &salt,
                        &salt_len, &server_key, &key_len) == 0);
    assert(salt && salt_len == SRP_SALT_SIZE && server_key && key_len > 0 && key_len <= SRP_PK_SIZE);
    free((void *) server_key);

    /* A stage-1 exchange does not authenticate the all-zero session key. */
    assert(srp_confirm_pair_setup(session, pairing, epk, tag) < 0);
    assert(srp_validate_proof(session, pairing, NULL, 1, proof, 20, sizeof(proof)) < 0);
    assert(srp_validate_proof(session, pairing, public_key, 0, proof, 20, sizeof(proof)) < 0);
    assert(srp_validate_proof(session, pairing, public_key, SRP_PK_SIZE + 1,
                              proof, 20, sizeof(proof)) < 0);
    assert(srp_validate_proof(session, pairing, public_key, sizeof(public_key),
                              proof, 19, sizeof(proof)) < 0);
    assert(srp_validate_proof(session, pairing, public_key, sizeof(public_key),
                              proof, SRP_M2_SIZE + 1, sizeof(proof)) < 0);
    assert(srp_validate_proof(session, pairing, public_key, sizeof(public_key),
                              proof, 20, SRP_M2_SIZE + 1) < 0);
    assert(srp_validate_proof(session, pairing, public_key, sizeof(public_key),
                              proof, 20, sizeof(proof)) < 0);
    assert(srp_confirm_pair_setup(session, pairing, epk, tag) < 0);

    pairing_session_destroy(session);
    pairing_destroy(pairing);
    puts("SRP pairing: 18 state and bounds assertions passed");
    return 0;
}
