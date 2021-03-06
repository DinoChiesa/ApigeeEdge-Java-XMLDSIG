// Copyright 2018-2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.apigee.edgecallouts.xmldsig;

import com.apigee.flow.message.MessageContext;
import com.google.apigee.util.XmlUtils;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import javax.xml.bind.DatatypeConverter;
import org.w3c.dom.Document;

public abstract class XmlDsigCalloutBase {
  private static final String _varprefix = "xmldsig_";
  private Map properties; // read-only
  private static final String variableReferencePatternString = "(.*?)\\{([^\\{\\} ]+?)\\}(.*?)";
  private static final Pattern variableReferencePattern =
      Pattern.compile(variableReferencePatternString);

  public XmlDsigCalloutBase(Map properties) {
    this.properties = properties;
  }

  static String varName(String s) {
    return _varprefix + s;
  }

  protected Document getDocument(MessageContext msgCtxt) throws Exception {
    String source = getSimpleOptionalProperty("source", msgCtxt);
    if (source == null) {
      return XmlUtils.parseXml(msgCtxt.getMessage().getContentAsStream());
    }
    String text = (String) msgCtxt.getVariable(source);
    if (text == null) {
      throw new IllegalStateException("source variable resolves to null");
    }
    return XmlUtils.parseXml(text);
  }

  protected boolean getDebug() {
    String value = (String) this.properties.get("debug");
    if (value == null) return false;
    if (value.trim().toLowerCase().equals("true")) return true;
    return false;
  }

  protected String getOutputVar(MessageContext msgCtxt) throws Exception {
    String dest = getSimpleOptionalProperty("output-variable", msgCtxt);
    if (dest == null) {
      return "message.content";
    }
    return dest;
  }

  protected String getSimpleOptionalProperty(String propName, MessageContext msgCtxt) {
    String value = (String) this.properties.get(propName);
    if (value == null) {
      return null;
    }
    value = value.trim();
    if (value.equals("")) {
      return null;
    }
    value = resolvePropertyValue(value, msgCtxt);
    if (value == null || value.equals("")) {
      return null;
    }
    return value;
  }

  protected String getSimpleRequiredProperty(String propName, MessageContext msgCtxt)
      throws IllegalStateException {
    String value = (String) this.properties.get(propName);
    if (value == null) {
      throw new IllegalStateException(propName + " resolves to an empty string");
    }
    value = value.trim();
    if (value.equals("")) {
      throw new IllegalStateException(propName + " resolves to an empty string");
    }
    value = resolvePropertyValue(value, msgCtxt);
    if (value == null || value.equals("")) {
      throw new IllegalStateException(propName + " resolves to an empty string");
    }
    return value;
  }

  // If the value of a property contains any pairs of curlies,
  // eg, {apiproxy.name}, then "resolve" the value by de-referencing
  // the context variables whose names appear between curlies.
  private String resolvePropertyValue(String spec, MessageContext msgCtxt) {
    Matcher matcher = variableReferencePattern.matcher(spec);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(sb, "");
      sb.append(matcher.group(1));
      Object v = msgCtxt.getVariable(matcher.group(2));
      if (v != null) {
        sb.append((String) v);
      }
      sb.append(matcher.group(3));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  protected void setExceptionVariables(Exception exc1, MessageContext msgCtxt) {
    String error = exc1.toString();
    msgCtxt.setVariable(varName("exception"), error);
    System.out.printf("Exception: %s\n", error);
    int ch = error.lastIndexOf(':');
    if (ch >= 0) {
      msgCtxt.setVariable(varName("error"), error.substring(ch + 2).trim());
    } else {
      msgCtxt.setVariable(varName("error"), error);
    }
  }

  enum IssuerNameStyle {
    NOT_SPECIFIED,
    SHORT,
    SUBJECT_DN
  }

  protected IssuerNameStyle getIssuerNameStyle(MessageContext msgCtxt) {
    String kitString = getSimpleOptionalProperty("issuer-name-style", msgCtxt);
    if (kitString == null) return IssuerNameStyle.SHORT;
    kitString = kitString.trim().toUpperCase();
    if (kitString.equals("SHORT")) return IssuerNameStyle.SHORT;
    if (kitString.equals("SUBJECT_DN")) return IssuerNameStyle.SUBJECT_DN;
    msgCtxt.setVariable(varName("warning"), "unrecognized issuer-name-style");
    return IssuerNameStyle.SHORT;
  }


  protected static String reformIndents(String s) {
    return s.trim().replaceAll("([\\r|\\n|\\r\\n] *)", "\n");
  }

  protected static String getCommonName(X500Principal principal)
    throws InvalidNameException {
    LdapName ldapDN = new LdapName(principal.getName());
    String cn = null;
    for (Rdn rdn : ldapDN.getRdns()) {
      // System.out.println(rdn.getType() + " -> " + rdn.getValue());
      if (rdn.getType().equals("CN")) {
        cn = rdn.getValue().toString();
      }
    }
    return cn;
  }

  protected static Certificate certificateFromPEM(String certificateString)
      throws KeyException {
    try {
      CertificateFactory certFactory = CertificateFactory.getInstance("X.509", "BC");
      certificateString = reformIndents(certificateString);
      Certificate certificate =
        certFactory.generateCertificate(
              new ByteArrayInputStream(certificateString.getBytes(StandardCharsets.UTF_8)));
      return certificate;
    } catch (Exception ex) {
      throw new KeyException("cannot instantiate certificate", ex);
    }
  }

  protected static String getThumbprintBase64(X509Certificate certificate)
      throws NoSuchAlgorithmException, CertificateEncodingException {
    return Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
  }

  protected static String getThumbprintHex(X509Certificate certificate)
      throws NoSuchAlgorithmException, CertificateEncodingException {
    return DatatypeConverter.printHexBinary(
            MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()))
        .toLowerCase();
  }

  protected static String getStackTraceAsString(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    return sw.toString();
  }
}
