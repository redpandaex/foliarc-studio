package studio.foliarc.biz.novel.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;

@Service
public class BookExportService {

    private static final String EPUB_MIMETYPE = "application/epub+zip";
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\h*\\n(?:\\h*\\n)*");
    private static final String CONTAINER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """;
    private static final String EPUB_CSS = """
            body { margin: 6%; font-family: serif; line-height: 1.75; }
            h1, h2 { line-height: 1.3; page-break-after: avoid; }
            p { margin: 0 0 0.8em; orphans: 2; widows: 2; }
            .volume { font-size: 0.9em; }
            """;

    private final BookService books;
    private final VolumeMapper volumeMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterSectionMapper sectionMapper;

    public BookExportService(BookService books, VolumeMapper volumeMapper, ChapterMapper chapterMapper,
                             ChapterSectionMapper sectionMapper) {
        this.books = books;
        this.volumeMapper = volumeMapper;
        this.chapterMapper = chapterMapper;
        this.sectionMapper = sectionMapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportedBook exportBook(UUID ownerId, UUID bookId, String format) {
        Format outputFormat = Format.parse(format);
        BookEntity book = books.requireOwnedBook(ownerId, bookId);
        List<ExportVolume> volumes = loadVolumes(bookId);
        byte[] content = switch (outputFormat) {
            case MD -> renderText(book, volumes, true);
            case TXT -> renderText(book, volumes, false);
            case EPUB -> renderEpub(book, volumes);
        };
        return new ExportedBook(book.getTitle(), outputFormat.extension, outputFormat.mediaType, content);
    }

    private List<ExportVolume> loadVolumes(UUID bookId) {
        List<VolumeEntity> volumes = volumeMapper.selectList(Wrappers.<VolumeEntity>lambdaQuery()
                .eq(VolumeEntity::getBookId, bookId).orderByAsc(VolumeEntity::getVolumeNo));
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getBookId, bookId).orderByAsc(ChapterEntity::getChapterNo));
        Map<UUID, List<ChapterSectionEntity>> sectionsByChapter = sectionMapper
                .selectList(Wrappers.<ChapterSectionEntity>lambdaQuery().eq(ChapterSectionEntity::getBookId, bookId)
                        .orderByAsc(ChapterSectionEntity::getNarrativeOrder))
                .stream().collect(Collectors.groupingBy(ChapterSectionEntity::getChapterId));

        Set<UUID> volumeIds = volumes.stream().map(VolumeEntity::getId).collect(Collectors.toSet());
        UUID defaultVolumeId = volumes.stream().filter(volume -> Integer.valueOf(1).equals(volume.getVolumeNo()))
                .map(VolumeEntity::getId).findFirst().orElse(null);
        Map<UUID, List<ExportChapter>> chaptersByVolume = new HashMap<>();
        for (ChapterEntity chapter : chapters) {
            UUID volumeId = volumeIds.contains(chapter.getVolumeId()) ? chapter.getVolumeId() : defaultVolumeId;
            var exportChapter = toExportChapter(chapter, sectionsByChapter.getOrDefault(chapter.getId(), List.of()));
            chaptersByVolume.computeIfAbsent(volumeId, ignored -> new ArrayList<>()).add(exportChapter);
        }

        List<ExportVolume> result = new ArrayList<>();
        List<ExportChapter> unassigned = chaptersByVolume.getOrDefault(null, List.of());
        boolean multipleVolumes = volumes.size() + (unassigned.isEmpty() ? 0 : 1) > 1;
        if (!unassigned.isEmpty()) {
            result.add(new ExportVolume(multipleVolumes ? "未分卷" : "", unassigned));
        }
        for (VolumeEntity volume : volumes) {
            String title = inline(volume.getTitle());
            if (title.isEmpty() && multipleVolumes) {
                title = "第" + volume.getVolumeNo() + "卷";
            }
            result.add(new ExportVolume(title, chaptersByVolume.getOrDefault(volume.getId(), List.of())));
        }
        return result;
    }

    private static ExportChapter toExportChapter(ChapterEntity chapter, List<ChapterSectionEntity> sections) {
        String number = "第" + chapter.getChapterNo() + "章";
        String title = inline(chapter.getTitle());
        String heading = title.isEmpty() ? number : number + " " + title;
        if (sections.isEmpty()) {
            return new ExportChapter(heading, List.of(new ExportSection("", chapter.getContent())));
        }
        if (sections.size() == 1) {
            return new ExportChapter(heading, List.of(new ExportSection("", sections.getFirst().getContent())));
        }
        return new ExportChapter(heading, sections.stream().map(section -> {
            String name = inline(section.getTitle());
            return new ExportSection(name.isEmpty() ? "第" + section.getSectionNo() + "节" : name, section.getContent());
        }).toList());
    }

    private static byte[] renderText(BookEntity book, List<ExportVolume> volumes, boolean markdown) {
        StringBuilder text = new StringBuilder();
        text.append(markdown ? "# " : "").append(inline(book.getTitle())).append("\n\n");
        if (book.getGenre() != null && !book.getGenre().isBlank()) {
            text.append(markdown ? "**题材：**" : "题材：").append(inline(book.getGenre())).append("\n\n");
        }
        if (book.getSynopsis() != null && !book.getSynopsis().isBlank()) {
            text.append(markdown ? "**简介**\n\n" : "简介\n\n");
            appendTextParagraphs(text, book.getSynopsis());
        }
        for (ExportVolume volume : volumes) {
            if (!volume.title().isEmpty()) {
                text.append(markdown ? "## " : "==== ").append(volume.title()).append(markdown ? "\n\n" : " ====\n\n");
            }
            for (ExportChapter chapter : volume.chapters()) {
                text.append(markdown ? "### " : "—— ").append(chapter.title()).append(markdown ? "\n\n" : " ——\n\n");
                for (ExportSection section : chapter.sections()) {
                    if (!section.title().isEmpty()) {
                        text.append(markdown ? "#### " : "").append(section.title()).append("\n\n");
                    }
                    appendTextParagraphs(text, section.content());
                }
            }
        }
        return (text.toString().stripTrailing() + "\n").getBytes(UTF_8);
    }

    private static byte[] renderEpub(BookEntity book, List<ExportVolume> volumes) {
        String title = inline(book.getTitle());
        StringBuilder manifest = new StringBuilder("""
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="style" href="style.css" media-type="text/css"/>
                """);
        StringBuilder spine = new StringBuilder();
        StringBuilder toc = new StringBuilder();
        addEpubPage(manifest, spine, toc, "title-page", title);

        List<EpubChapter> chapters = new ArrayList<>();
        for (ExportVolume volume : volumes) {
            if (volume.chapters().isEmpty()) {
                continue;
            }
            boolean hasTitle = !volume.title().isEmpty();
            if (hasTitle) {
                toc.append("<li><span>").append(xml(volume.title())).append("</span><ol>\n");
            }
            String volumeTitle = volume.title();
            for (ExportChapter chapter : volume.chapters()) {
                String id = String.format(Locale.ROOT, "chapter-%04d", chapters.size() + 1);
                addEpubPage(manifest, spine, toc, id, chapter.title());
                chapters.add(new EpubChapter(id, volumeTitle, chapter));
                volumeTitle = "";
            }
            if (hasTitle) {
                toc.append("</ol></li>\n");
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, UTF_8)) {
            writeMimetype(zip);
            writeEntry(zip, "META-INF/container.xml", CONTAINER_XML);
            writeEntry(zip, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">urn:uuid:%s</dc:identifier>
                        <dc:title>%s</dc:title>
                        <dc:language>zh-CN</dc:language>
                        <meta property="dcterms:modified">%s</meta>
                      </metadata>
                      <manifest>%s</manifest>
                      <spine>%s</spine>
                    </package>
                    """.formatted(book.getId(), xml(title), Instant.now().truncatedTo(ChronoUnit.SECONDS), manifest, spine));
            writeEntry(zip, "OEBPS/nav.xhtml", xhtml("目录", """
                    <nav epub:type="toc" id="toc">
                      <h1>目录</h1>
                      <ol>%s</ol>
                    </nav>
                    """.formatted(toc)));
            writeEntry(zip, "OEBPS/style.css", EPUB_CSS);
            StringBuilder titlePage = new StringBuilder("<h1>").append(xml(title)).append("</h1>\n");
            if (book.getGenre() != null && !book.getGenre().isBlank()) {
                titlePage.append("<p>题材：").append(xml(inline(book.getGenre()))).append("</p>\n");
            }
            appendXhtmlParagraphs(titlePage, book.getSynopsis());
            writeEntry(zip, "OEBPS/title-page.xhtml", xhtml(title, titlePage));
            for (EpubChapter chapter : chapters) {
                writeEntry(zip, "OEBPS/" + chapter.id() + ".xhtml", renderChapter(chapter));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not generate EPUB", ex);
        }
        return bytes.toByteArray();
    }

    private static void addEpubPage(StringBuilder manifest, StringBuilder spine, StringBuilder toc,
                                    String id, String title) {
        String href = id + ".xhtml";
        manifest.append("<item id=\"").append(id).append("\" href=\"").append(href)
                .append("\" media-type=\"application/xhtml+xml\"/>\n");
        spine.append("<itemref idref=\"").append(id).append("\"/>\n");
        toc.append("<li><a href=\"").append(href).append("\">").append(xml(title)).append("</a></li>\n");
    }

    private static String renderChapter(EpubChapter page) {
        ExportChapter chapter = page.chapter();
        StringBuilder body = new StringBuilder();
        if (!page.volumeTitle().isEmpty()) {
            body.append("<p class=\"volume\">").append(xml(page.volumeTitle())).append("</p>\n");
        }
        body.append("<h1>").append(xml(chapter.title())).append("</h1>\n");
        for (ExportSection section : chapter.sections()) {
            if (!section.title().isEmpty()) {
                body.append("<h2>").append(xml(section.title())).append("</h2>\n");
            }
            appendXhtmlParagraphs(body, section.content());
        }
        return xhtml(chapter.title(), body);
    }

    private static String xhtml(String title, CharSequence body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"
                      xml:lang="zh-CN" lang="zh-CN">
                  <head>
                    <title>%s</title>
                    <link rel="stylesheet" type="text/css" href="style.css"/>
                  </head>
                  <body>
                %s  </body>
                </html>
                """.formatted(xml(title), body);
    }

    private static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] bytes = EPUB_MIMETYPE.getBytes(UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry mimetype = new ZipEntry("mimetype");
        mimetype.setMethod(ZipEntry.STORED);
        mimetype.setSize(bytes.length);
        mimetype.setCompressedSize(bytes.length);
        mimetype.setCrc(crc.getValue());
        zip.putNextEntry(mimetype);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeEntry(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(UTF_8));
        zip.closeEntry();
    }

    private static void appendTextParagraphs(StringBuilder text, String content) {
        for (String paragraph : paragraphs(content)) {
            text.append(paragraph).append("\n\n");
        }
    }

    private static void appendXhtmlParagraphs(StringBuilder body, String content) {
        for (String paragraph : paragraphs(content)) {
            body.append("<p>").append(xml(paragraph).replace("\n", "<br/>\n")).append("</p>\n");
        }
    }

    private static List<String> paragraphs(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.strip().replace("\r\n", "\n").replace('\r', '\n');
        return Arrays.asList(PARAGRAPH_BREAK.split(normalized));
    }

    private static String inline(String text) {
        return text == null ? "" : text.replaceAll("\\R", " ").strip();
    }

    private static String xml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.appendCodePoint(isXmlCharacter(codePoint) ? codePoint : 0xFFFD);
            }
        });
        return escaped.toString();
    }

    private static boolean isXmlCharacter(int codePoint) {
        return codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || codePoint >= 0x10000;
    }

    public record ExportedBook(String title, String extension, MediaType contentType, byte[] content) {}

    private record ExportVolume(String title, List<ExportChapter> chapters) {}

    private record ExportChapter(String title, List<ExportSection> sections) {}

    private record ExportSection(String title, String content) {}

    private record EpubChapter(String id, String volumeTitle, ExportChapter chapter) {}

    private enum Format {
        MD("md", new MediaType("text", "markdown", UTF_8)),
        TXT("txt", new MediaType("text", "plain", UTF_8)),
        EPUB("epub", MediaType.parseMediaType(EPUB_MIMETYPE));

        private final String extension;
        private final MediaType mediaType;

        Format(String extension, MediaType mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        private static Format parse(String value) {
            return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
                case "md" -> MD;
                case "txt" -> TXT;
                case "epub" -> EPUB;
                default -> throw BizException.badRequest("UNSUPPORTED_EXPORT_FORMAT", "导出格式仅支持 md、txt 或 epub");
            };
        }
    }
}
