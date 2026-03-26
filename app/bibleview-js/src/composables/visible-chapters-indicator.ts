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

import {ComputedRef, ref, Ref, watch} from "vue";
import {throttle} from "lodash";
import {setupWindowEventListener} from "@/utils";
import {AnyDocument, BibleDocumentType} from "@/types/documents";
import {AppSettings, CalculatedConfig} from "@/composables/config";

export type VisibleChapter = {
    label: string
    key: string
}

export function asVisibleChapter(document: AnyDocument): VisibleChapter | null {
    if (document.type !== "bible") {
        return null;
    }
    const bibleDocument = document as BibleDocumentType;
    return {
        label: `${bibleDocument.bibleBookName} ${Math.max(1, bibleDocument.chapterNumber)}`,
        key: `${bibleDocument.bibleBookName}:${Math.max(1, bibleDocument.chapterNumber)}`,
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

    function detectVisibleChapters() {
        if (!mounted.value || documents.length === 0) {
            if (visibleChapters.value.length > 0) {
                visibleChapters.value = [];
            }
            return;
        }
        const topBoundary = Math.max(0, calculatedConfig.value.topOffset + (lineHeight.value * 0.2));
        const bottomBoundary = window.innerHeight;

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
    }

    const onScroll = throttle(detectVisibleChapters, 500, {leading: true, trailing: true});
    setupWindowEventListener("scroll", onScroll);

    watch(() => documents.map(d => d.id), () => {
        detectVisibleChapters();
    });

    watch(() => [
        appSettings.topOffset,
        calculatedConfig.value.topOffset,
        mounted.value,
    ], () => detectVisibleChapters());

    const visible = ref(false);
    watch(
        () => [
            visibleChapters.value.length,
            appSettings.topOffset,
            documents.length,
            documents[0]?.type,
        ],
        () => {
            visible.value = isFullscreenFromOffsets(appSettings)
                && documents.length > 0
                && documents[0].type === "bible"
                && visibleChapters.value.length > 0;
        },
        {immediate: true}
    );

    return {
        visible,
        visibleChapters,
    };
}
