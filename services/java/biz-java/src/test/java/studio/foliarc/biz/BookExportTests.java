package studio.foliarc.biz;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.GlobalExceptionHandler;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.controller.BookController;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.novel.service.BookExportService;
import studio.foliarc.biz.novel.service.BookService;

class BookExportTests {

    private static final String TITLE = "霜月 & <舟>*'";
    private static final String ENCODED_TITLE = "%E9%9C%9C%E6%9C%88%20%26%20%3C%E8%88%9F%3E%2A%27";
    private static final String XHTML_NS = "http://www.w3.org/1999/xhtml";
    private static final String OPF_NS = "http://www.idpf.org/2007/opf";
    private static final String EXPORT = "/api/v1/books/{bookId}/export";

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final BookMapper bookMapper = mock(BookMapper.class);
    private final VolumeMapper volumeMapper = mock(VolumeMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final ChapterSectionMapper sectionMapper = mock(ChapterSectionMapper.class);
    private final BookService books = new BookService(bookMapper, chapterMapper);
    private final BookExportService exports = new BookExportService(books, volumeMapper, chapterMapper, sectionMapper);
    private MockMvc mvc;

    @BeforeAll
    static void initializeQueriesForMockedMappers() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "book-export");
        for (var entity : List.of(VolumeEntity.class, ChapterEntity.class, ChapterSectionEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        authenticate(ownerId);
        var book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setTitle(TITLE);
        book.setGenre("幻想");
        book.setSynopsis("一场关于海与月的旅行。");
        when(bookMapper.selectById(bookId)).thenReturn(book);
        mvc = MockMvcBuilders.standaloneSetup(new BookController(books, mock(StoryMemoryContextService.class), exports))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void markdownContainsMetadataVolumesAndFullOrderedChapterText() throws Exception {
        stubNovel();

        String text = mvc.perform(get(EXPORT, bookId).param("format", "md"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType("text", "markdown", UTF_8)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, disposition("md")))
                .andReturn().getResponse().getContentAsString(UTF_8);

        assertThat(text).startsWith("# " + TITLE + "\n")
                .contains("**题材：**幻想", "一场关于海与月的旅行。", "## 上卷 & 起航",
                        "### 第2章 海 & 风", "#### 小节 <一>", "晨曦 <渡口> & 风\n\n“启程”",
                        "#### 第2节", "灯塔 😀", "## 第2卷", "### 第1章 归来", "旧章正文")
                .doesNotContain("不应导出的旧合并正文", "\r");
        assertThat(text.indexOf("晨曦")).isLessThan(text.indexOf("灯塔"));
        assertThat(text.indexOf("### 第2章")).isLessThan(text.indexOf("### 第1章"));
    }

    @Test
    void plainTextHasReaderFriendlyDividersAndLegacyFallback() throws Exception {
        stubNovel();

        String text = mvc.perform(get(EXPORT, bookId).param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType("text", "plain", UTF_8)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, disposition("txt")))
                .andReturn().getResponse().getContentAsString(UTF_8);

        assertThat(text).startsWith(TITLE + "\n")
                .contains("==== 上卷 & 起航 ====", "—— 第2章 海 & 风 ——", "小节 <一>\n\n晨曦",
                        "第2节\n\n灯塔", "==== 第2卷 ====", "—— 第1章 归来 ——", "旧章正文")
                .doesNotContain("###", "不应导出的旧合并正文");
    }

    @Test
    void defaultEpubHasValidContainerManifestSpineAndEscapedChapterXhtml() throws Exception {
        stubNovel();

        byte[] bytes = mvc.perform(get(EXPORT, bookId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/epub+zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, disposition("epub")))
                .andReturn().getResponse().getContentAsByteArray();

        Map<String, byte[]> files = readEpub(bytes);
        assertThat(files).containsKeys("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml",
                "OEBPS/style.css", "OEBPS/title-page.xhtml", "OEBPS/chapter-0001.xhtml", "OEBPS/chapter-0002.xhtml");
        var container = parseXml(files.get("META-INF/container.xml"));
        var rootfile = (Element) container.getElementsByTagName("rootfile").item(0);
        assertThat(rootfile.getAttribute("full-path")).isEqualTo("OEBPS/content.opf");

        var opf = parseXml(files.get("OEBPS/content.opf"));
        assertThat(opf.getDocumentElement().getAttribute("version")).isEqualTo("3.0");
        assertThat(opf.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0).getTextContent())
                .isEqualTo(TITLE);
        assertThat(opf.getElementsByTagName("dc:language").item(0).getTextContent()).isEqualTo("zh-CN");
        assertThat(opf.getElementsByTagName("dc:identifier").item(0).getTextContent()).isEqualTo("urn:uuid:" + bookId);
        assertThat(Instant.parse(opf.getElementsByTagNameNS(OPF_NS, "meta").item(0).getTextContent())).isNotNull();

        var manifest = opf.getElementsByTagNameNS(OPF_NS, "item");
        Map<String, String> manifestHrefs = new LinkedHashMap<>();
        for (int i = 0; i < manifest.getLength(); i++) {
            var item = (Element) manifest.item(i);
            assertThat(files).containsKey("OEBPS/" + item.getAttribute("href"));
            manifestHrefs.put(item.getAttribute("id"), item.getAttribute("href"));
        }
        var spine = opf.getElementsByTagNameNS(OPF_NS, "itemref");
        assertThat(spine.getLength()).isEqualTo(3);
        assertThat(manifestHrefs.get(((Element) spine.item(1)).getAttribute("idref"))).isEqualTo("chapter-0001.xhtml");

        var nav = parseXml(files.get("OEBPS/nav.xhtml"));
        var toc = (Element) nav.getElementsByTagNameNS(XHTML_NS, "nav").item(0);
        assertThat(toc.getAttributeNS("http://www.idpf.org/2007/ops", "type")).isEqualTo("toc");
        var link = (Element) toc.getElementsByTagNameNS(XHTML_NS, "a").item(1);
        assertThat(link.getAttribute("href")).isEqualTo("chapter-0001.xhtml");
        assertThat(link.getTextContent()).isEqualTo("第2章 海 & 风");

        byte[] chapter = files.get("OEBPS/chapter-0001.xhtml");
        assertThat(new String(chapter, UTF_8)).contains("小节 &lt;一&gt;", "晨曦 &lt;渡口&gt; &amp; 风");
        var chapterDoc = parseXml(chapter);
        var headings = chapterDoc.getElementsByTagNameNS(XHTML_NS, "h2");
        assertThat(headings.getLength()).isEqualTo(2);
        assertThat(headings.item(0).getTextContent()).isEqualTo("小节 <一>");
        assertThat(headings.item(1).getTextContent()).isEqualTo("第2节");
        assertThat(chapterDoc.getDocumentElement().getTextContent()).contains("晨曦 <渡口> & 风", "灯塔 😀")
                .doesNotContain("不应导出的旧合并正文");
        assertThat(parseXml(files.get("OEBPS/chapter-0002.xhtml")).getDocumentElement().getTextContent())
                .contains("旧章正文");
    }

    @Test
    void emptyEpubStillHasAReadableSpineAndToc() throws Exception {
        Map<String, byte[]> files = readEpub(exports.exportBook(ownerId, bookId, "epub").content());
        var title = parseXml(files.get("OEBPS/title-page.xhtml")).getElementsByTagNameNS(XHTML_NS, "h1").item(0);
        assertThat(title.getTextContent()).isEqualTo(TITLE);
        assertThat(new String(files.get("OEBPS/content.opf"), UTF_8)).contains("<itemref idref=\"title-page\"/>");
        assertThat(new String(files.get("OEBPS/nav.xhtml"), UTF_8)).contains("href=\"title-page.xhtml\"");
    }

    @Test
    void rejectsUnsupportedFormatsAndBooksOwnedByAnotherAuthor() throws Exception {
        mvc.perform(get(EXPORT, bookId).param("format", "pdf"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_EXPORT_FORMAT"));

        authenticate(UUID.randomUUID());
        mvc.perform(get(EXPORT, bookId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOK_ACCESS_DENIED"));
        verifyNoInteractions(volumeMapper, chapterMapper, sectionMapper);
    }

    private void stubNovel() {
        var firstVolume = new VolumeEntity();
        firstVolume.setId(UUID.randomUUID());
        firstVolume.setVolumeNo(1);
        firstVolume.setTitle("上卷 & 起航");
        var secondVolume = new VolumeEntity();
        secondVolume.setId(UUID.randomUUID());
        secondVolume.setVolumeNo(2);
        when(volumeMapper.selectList(any())).thenReturn(List.of(firstVolume, secondVolume));

        var legacy = new ChapterEntity();
        legacy.setId(UUID.randomUUID());
        legacy.setVolumeId(secondVolume.getId());
        legacy.setChapterNo(1);
        legacy.setTitle("归来");
        legacy.setContent("旧章正文");
        var split = new ChapterEntity();
        split.setId(UUID.randomUUID());
        split.setChapterNo(2);
        split.setTitle("海 & 风");
        split.setContent("不应导出的旧合并正文");
        when(chapterMapper.selectList(any())).thenReturn(List.of(legacy, split));

        var firstSection = new ChapterSectionEntity();
        firstSection.setChapterId(split.getId());
        firstSection.setSectionNo(1);
        firstSection.setTitle("小节 <一>");
        firstSection.setContent("晨曦 <渡口> & 风\r\n\r\n“启程”");
        var secondSection = new ChapterSectionEntity();
        secondSection.setChapterId(split.getId());
        secondSection.setSectionNo(2);
        secondSection.setContent("灯塔 😀");
        when(sectionMapper.selectList(any())).thenReturn(List.of(firstSection, secondSection));
    }

    private static void authenticate(UUID id) {
        var principal = new AuthenticatedUser(id, "author@example.test", "author", "AUTHOR");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static String disposition(String extension) {
        return "attachment; filename=\"book." + extension + "\"; filename*=UTF-8''" + ENCODED_TITLE + "." + extension;
    }

    private static Map<String, byte[]> readEpub(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), UTF_8)) {
            ZipEntry mimetype = zip.getNextEntry();
            assertThat(mimetype.getName()).isEqualTo("mimetype");
            assertThat(mimetype.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(mimetype.getExtra()).isNullOrEmpty();
            byte[] mimeBytes = zip.readAllBytes();
            assertThat(new String(mimeBytes, UTF_8)).isEqualTo("application/epub+zip");
            assertThat(mimetype.getSize()).isEqualTo(mimeBytes.length);
            CRC32 crc = new CRC32();
            crc.update(mimeBytes);
            assertThat(mimetype.getCrc()).isEqualTo(crc.getValue());
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
}
