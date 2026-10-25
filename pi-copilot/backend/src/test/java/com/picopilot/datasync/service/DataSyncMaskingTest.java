package com.picopilot.datasync.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataSyncMaskingTest {

    @Test
    void masksValuesSoRawPiNeverReachesTheBrowser() {
        assertThat(DataSyncService.mask("asha@example.com")).isEqualTo("as**@example.com");
        assertThat(DataSyncService.mask("a@x.io")).isEqualTo("a**@x.io");
        assertThat(DataSyncService.mask("+919800000001")).isEqualTo("+9******01");
        assertThat(DataSyncService.mask("Rao")).isEqualTo("R**");
        assertThat(DataSyncService.mask("Meera")).isEqualTo("Me*ra");
        assertThat(DataSyncService.mask(null)).isNull();
    }

    @Test
    void ciphertextPreviewIsShortened() {
        assertThat(DataSyncService.preview("enc:v1:lpVufZA+QbkBq8LEKTZhEPEyPuzYI01I/xyz"))
            .isEqualTo("enc:v1:lpVufZA+QbkBq8LEKTZhE…");
        assertThat(DataSyncService.preview(null)).isNull();
    }
}
