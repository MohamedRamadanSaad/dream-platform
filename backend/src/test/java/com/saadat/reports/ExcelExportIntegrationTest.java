package com.saadat.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.service.DreamService;
import com.saadat.users.domain.User;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ExcelExportIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamService dreamService;

    @Test
    void exportsMatchingDreamsAsAnXlsxWorkbook() throws Exception {
        User interpreter = createUser("xlsx-interp", Role.INTERPRETER);
        User user = createUser("xlsx-user", Role.USER);
        user.setName("Huda Salem");
        user.setBirthDate(LocalDate.of(1990, 1, 15));
        user = userRepository.save(user);
        String token = "tok" + UUID.randomUUID().toString().substring(0, 8);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID dream = draft(user, "I saw a quiet river and a green tree near my house " + token + " in the dream.");
        dreamService.submit(user.getId(), List.of(dream), null);
        draft(user, "This draft must never be exported " + token + " because drafts are private.");

        MvcResult result = mvc.perform(get(ApiPaths.Admin.DREAMS_EXPORT).param("q", token)
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType())
                .startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;").contains("filename=\"dreams-").contains(".xlsx\"");
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.isRightToLeft()).isFalse();
            XSSFRow header = sheet.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 13);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Dream ID");
            assertThat(header.getCell(12).getStringCellValue()).isEqualTo("Messages");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getLastRowNum()).as("one matching non-draft dream").isEqualTo(1);
            XSSFRow row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(dream.toString());
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC); // a real date cell
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("In review");
            assertThat(row.getCell(5).getStringCellValue()).isEqualTo("Huda Salem");
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo(user.getEmail());
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo("Male");
            assertThat(row.getCell(8).getNumericCellValue()).isGreaterThan(30);
            assertThat(row.getCell(9).getStringCellValue()).isEqualTo("Saudi Arabia");
            assertThat(row.getCell(10).getStringCellValue()).contains(token);
            assertThat(row.getCell(12).getNumericCellValue()).isZero();
        }

        // Arabic headers on a right-to-left sheet; a non-matching status filter leaves only the header
        MvcResult arabic = mvc.perform(get(ApiPaths.Admin.DREAMS_EXPORT).param("q", token)
                        .param("status", "INTERPRETED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(arabic.getResponse().getContentAsByteArray()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.isRightToLeft()).isTrue();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("رقم الرؤيا");
            assertThat(sheet.getLastRowNum()).isZero();
        }
    }

    @Test
    void usersCannotExport() throws Exception {
        User user = createUser("xlsx-plain", Role.USER);
        mvc.perform(get(ApiPaths.Admin.DREAMS_EXPORT).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }

    private UUID draft(User user, String text) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", text, "gender", "MALE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }
}
