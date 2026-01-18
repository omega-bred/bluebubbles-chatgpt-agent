package io.breland.bbagent.server.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  // Default to a 500 for generic exceptions
  @ExceptionHandler(Exception.class)
  public ResponseEntity<String> handleGenericException(Exception e) {
    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
  }

  //    @ExceptionHandler(EntityAlreadyExistsException.class)
  //    public ResponseEntity<String>
  // handleEntityAlreadyExistsException(EntityAlreadyExistsException e) {
  //        return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
  //    }
  //
  //    @ExceptionHandler(EntityNotFoundException.class)
  //    public ResponseEntity<String> handleEntityNotFoundException(EntityNotFoundException e) {
  //        return new ResponseEntity<>(e.getMessage(), HttpStatus.NOT_FOUND);
  //    }
}
