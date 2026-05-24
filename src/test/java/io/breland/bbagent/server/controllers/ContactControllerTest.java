package io.breland.bbagent.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.generated.model.ContactMessageResponse;
import io.breland.bbagent.server.config.SecurityConfig;
import io.breland.bbagent.server.contact.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ContactController.class)
@Import(SecurityConfig.class)
class ContactControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockBean private ContactService contactService;

  @Test
  void contactCreateMessageAllowsAnonymousSubmissions() throws Exception {
    when(contactService.createMessage(any(), any(HttpServletRequest.class), isNull()))
        .thenReturn(new ContactMessageResponse().status("accepted").messageId("BLU-123"));

    mockMvc.perform(postContact()).andExpect(status().isOk());

    verify(contactService).createMessage(any(), any(HttpServletRequest.class), isNull());
  }

  @Test
  void contactCreateMessagePassesAuthenticatedJwtWhenPresent() throws Exception {
    when(contactService.createMessage(any(), any(HttpServletRequest.class), any(Jwt.class)))
        .thenReturn(new ContactMessageResponse().status("accepted").messageId("BLU-124"));

    mockMvc
        .perform(postContact().with(jwt().jwt(token -> token.subject("sub-1"))))
        .andExpect(status().isOk());

    ArgumentCaptor<Jwt> jwtCaptor = ArgumentCaptor.forClass(Jwt.class);
    verify(contactService).createMessage(any(), any(HttpServletRequest.class), jwtCaptor.capture());
    assertThat(jwtCaptor.getValue().getSubject()).isEqualTo("sub-1");
  }

  private MockHttpServletRequestBuilder postContact() {
    return post("/api/v1/contact/create.contactMessages")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "name": "Ada",
              "email": "ada@example.com",
              "subject": "Help",
              "message": "I need a hand.",
              "cap_token": "cap-token"
            }
            """);
  }
}
