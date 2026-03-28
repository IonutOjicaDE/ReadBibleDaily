/*
 * Copyright (c) 2026 Martin Denham, Sykerö Software / Tuomas Airaksinen and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

import {computed, ComputedRef, nextTick, ref, Ref, watch} from "vue";
import {throttle} from "lodash";
import {setupWindowEventListener} from "@/utils";
import type {AnyDocument} from "@/types/documents";
import type {AppSettings, CalculatedConfig} from "@/composables/config";

export type VisibleChapter = {
    label: string
    key: string
}

export function asVisibleChapter(document: AnyDocument): VisibleChapter | null {
    if (document.type !== "bible") {
        return null;
    }
    return {
        label: `${document.bibleBookName} ${Math.max(1, document.chapterNumber)}`,
        key: `${document.bibleBookName}:${Math.max(1, document.chapterNumber)}`,
    };
}

export function isFullscreenFromOffsets(appSettings: AppSettings): boolean {
    return appSettings.topOffset === 0;
}

export function useVisibleChaptersIndicator(
    documents: AnyDocument[],
    appSettings: AppSettings,
    calculatedConfig: CalculatedConfig,
    lineHeight: ComputedRef<number>,
    mounted: Ref<boolean>,
) {
    const visibleChapters = ref<VisibleChapter[]>([]);
    const canShowIndicator = computed(() =>
        mounted.value
        && appSettings.isFullscreen
        && isFullscreenFromOffsets(appSettings)
        && documents.length > 0
        && documents[0].type === "bible"
    );

    function detectVisibleChapters() {
        try {
            if (!canShowIndicator.value) {
                if (visibleChapters.value.length > 0) {
                    visibleChapters.value = [];
                }
                return;
            }
            const topBoundary = Math.max(0, calculatedConfig.value.topOffset + (lineHeight.value * 0.2));
            const bottomBoundary = Math.max(
                topBoundary + 1,
                window.innerHeight - appSettings.bottomOffset
            );

            const chapters: VisibleChapter[] = [];
            const chapterKeys = new Set<string>();

            for (const doc of documents) {
                if (doc.type !== "bible") {
                    continue;
                }
                const chapter = asVisibleChapter(doc);
                if (!chapter || chapterKeys.has(chapter.key)) {
                    continue;
                }
                const el = window.document.getElementById(`doc-${doc.id}`);
                if (!el) {
                    continue;
                }
                const rect = el.getBoundingClientRect();
                if (rect.top >= bottomBoundary) {
                    break;
                }
                const isVisible = rect.bottom > topBoundary && rect.top < bottomBoundary;
                if (isVisible) {
                    chapterKeys.add(chapter.key);
                    chapters.push(chapter);
                }
                if (chapters.length >= 2) {
                    break;
                }
            }

            const hasChanged = chapters.length !== visibleChapters.value.length
                || chapters.some((chapter, index) => chapter.key !== visibleChapters.value[index]?.key);

            if (hasChanged) {
                visibleChapters.value = chapters;
            }
        } catch (error) {
            console.error("Visible chapter detection failed", error);
            visibleChapters.value = [];
        }
    }

    const onScroll = throttle(detectVisibleChapters, 500, {leading: true, trailing: true});
    setupWindowEventListener("scroll", onScroll);
    setupWindowEventListener("resize", onScroll);

    watch(
        () => documents.map(d => d.id),
        async () => {
            await nextTick();
            detectVisibleChapters();
        },
        {flush: "post"}
    );

    watch(() => [
        appSettings.isFullscreen,
        appSettings.topOffset,
        calculatedConfig.value.topOffset,
        mounted.value,
    ], () => detectVisibleChapters());

    const visible = computed(() => canShowIndicator.value && visibleChapters.value.length > 0);

    return {
        visible,
        visibleChapters,
    };
}
