package studio.foliarc.biz.novel.service;

public final class WordCounter {

    private WordCounter() {
    }

    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int nonWhitespace = 0;
        for (int idx = 0; idx < text.length(); ) {
            int codePoint = text.codePointAt(idx);
            if (!Character.isWhitespace(codePoint)) {
                nonWhitespace++;
            }
            idx += Character.charCount(codePoint);
        }
        return nonWhitespace;
    }
}
