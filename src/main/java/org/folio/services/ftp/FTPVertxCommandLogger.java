package org.folio.services.ftp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import org.apache.commons.net.PrintCommandListener;
import io.vertx.core.logging.Logger;

public class FTPVertxCommandLogger extends OutputStream {

  public static PrintCommandListener getDefListener(Logger logger){
    return new PrintCommandListener(new PrintStream(new FTPVertxCommandLogger(logger)));
  }

  private final ByteArrayOutputStream baos = new ByteArrayOutputStream(2000);
  private final Logger logger;

  public FTPVertxCommandLogger(Logger logger) {
    this.logger = logger;
  }

  private String maskPassword(String line){
    if (line.contains("PASS")){
      String password = line.substring(5, line.length() - 3);
      line = "PASS "+ password.replaceAll("[^\\s\\\\]", "*");
    }
    return line;
  }

  @Override
  public void write(int b) {
    if (b == '\n') {
      String line = maskPassword(baos.toString());
      baos.reset();
      logger.info(line);
    } else {
      baos.write(b);
    }
  }

  @Override
  public void write(byte[] b, int off, int len) {
    baos.write(b, off, len);
  }

}