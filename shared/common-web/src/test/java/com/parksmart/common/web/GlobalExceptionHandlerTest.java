package com.parksmart.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = GlobalExceptionHandlerTest.TestApp.class)
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

  @Autowired MockMvc mvc;

  @Test
  void api_exception_becomes_problem_with_type_and_correlation_id() throws Exception {
    mvc.perform(get("/conflict").header(CorrelationId.HEADER, "abc-123"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/booking-overlap"))
        .andExpect(jsonPath("$.title").value("Overlap"))
        .andExpect(jsonPath("$.detail").value("Slot taken"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.correlationId").value("abc-123"))
        .andExpect(header().string(CorrelationId.HEADER, "abc-123"));
  }

  @Test
  void invalid_body_lists_field_errors() throws Exception {
    mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/validation-failed"))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void malformed_json_is_a_bad_request_problem() throws Exception {
    mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/bad-request"));
  }

  @Test
  void unexpected_exception_hides_internals() throws Exception {
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/internal-error"))
        .andExpect(jsonPath("$.detail").value(not(containsString("secret"))));
  }

  @Test
  void unsafe_correlation_id_is_replaced() throws Exception {
    mvc.perform(get("/conflict").header(CorrelationId.HEADER, "bad id\nwith newline"))
        .andExpect(header().string(CorrelationId.HEADER, not(containsString(" "))));
  }

  @Test
  void unsupported_method_gets_parksmart_type() throws Exception {
    mvc.perform(post("/boom"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/method-not-allowed"));
  }

  @SpringBootApplication
  @Import(TestController.class)
  static class TestApp {}

  record ThingRequest(@NotBlank @Size(max = 10) String name) {}

  @RestController
  static class TestController {
    @GetMapping("/conflict")
    void conflict() {
      throw ApiException.conflict("booking-overlap", "Overlap", "Slot taken");
    }

    @GetMapping("/boom")
    void boom() {
      throw new IllegalStateException("secret internal state");
    }

    @PostMapping("/things")
    ThingRequest create(@Valid @RequestBody ThingRequest request) {
      return request;
    }
  }
}
