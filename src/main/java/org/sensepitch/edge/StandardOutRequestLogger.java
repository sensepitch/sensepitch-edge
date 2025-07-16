package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * @author Jens Wilke
 */
public class StandardOutRequestLogger implements RequestLogger {

  private static final DateTimeFormatter CLF_TIME =
    DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

  @Override
  public void logRequest(RequestLogInfo info) {
    HttpRequest request = info.request();
    HttpResponse response = info.response();
    String remoteHost = "-";
    if (info.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) info.channel().remoteAddress();
      remoteHost = addr.getAddress().getHostAddress();
    }
    String admissionToken = request.headers().get(AdmissionHandler.ADMISSION_TOKEN_HEADER);
    if (admissionToken == null) {
      admissionToken = "-";
    }
    String time = ZonedDateTime.now().format(CLF_TIME);
    String requestLine = request.method() + " " +
      request.uri() + " " +
      request.protocolVersion();
    String referer = sanitize(request.headers().get(HttpHeaderNames.REFERER));
    String ua = sanitize(request.headers().get(HttpHeaderNames.USER_AGENT));
    String bypass = sanitize(request.headers().get(BypassCheck.HEADER));
    String host = sanitize(request.headers().get(HttpHeaderNames.HOST));
    int status = response.status().code();
    long delta = System.currentTimeMillis() - info.requestStartTime();
    // pattern "0.000" → at least one digit before the dot, exactly three after
    DecimalFormat df = new DecimalFormat("0.000");
    String deltaTime = df.format(delta / 1000.0);
    String ipTraits = sanitize(IpTraitsHandler.extract(request));
    String error = "-";
    if (info.error() != null) {
      error = sanitize(info.error().getMessage());
    }
    System.out.println("RQ0 " +
      host + " " + remoteHost + " \"" + ipTraits + "\" " + admissionToken + " ["+ time + "] "
      + requestLine + " " + status + " " + info.contentBytes() + " " + deltaTime + " \""
      + bypass + "\" \"" + ua + "\" " + referer + " \"" + error + "\"");
  }

  String sanitize(String s) {
    if (s == null) { return "-"; }
    if (s.indexOf('"') >= 0) {
      return s.replace("\"", "\\");
    }
    return s;
  }

}
