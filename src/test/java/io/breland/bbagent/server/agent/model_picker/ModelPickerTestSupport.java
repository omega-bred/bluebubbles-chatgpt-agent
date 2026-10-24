package io.breland.bbagent.server.agent.model_picker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.List;

public final class ModelPickerTestSupport {
  private ModelPickerTestSupport() {}

  public static ModelPicker standard() {
    return standard(ModelAccessService.STANDARD_RESPONSES_MODEL);
  }

  public static ModelPicker standard(String responsesModel) {
    ModelAccessService modelAccessService = mock(ModelAccessService.class);
    when(modelAccessService.resolve(any(IncomingMessage.class)))
        .thenReturn(
            new ModelAccessService.ModelAccess(
                null,
                false,
                ModelAccessService.STANDARD_MODEL_KEY,
                ModelAccessService.STANDARD_MODEL_LABEL,
                responsesModel,
                false,
                List.of()));
    return new ModelPicker(modelAccessService);
  }
}
