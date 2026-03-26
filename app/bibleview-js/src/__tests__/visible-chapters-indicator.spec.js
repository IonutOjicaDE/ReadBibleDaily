import {describe, expect, it} from "vitest";
import {asVisibleChapter, isFullscreenFromOffsets} from "@/composables/visible-chapters-indicator";

describe("visible chapter indicator helpers", () => {
    it("creates label for bible document", () => {
        const chapter = asVisibleChapter({
            type: "bible",
            bibleBookName: "Marcu",
            chapterNumber: 2,
        });

        expect(chapter).toStrictEqual({
            label: "Marcu 2",
            key: "Marcu:2",
        });
    });

    it("ignores non-bible documents", () => {
        expect(asVisibleChapter({type: "osis"})).toBeNull();
    });

    it("detects fullscreen from zero top offset", () => {
        expect(isFullscreenFromOffsets({topOffset: 0})).toBe(true);
        expect(isFullscreenFromOffsets({topOffset: 8})).toBe(false);
    });
});
