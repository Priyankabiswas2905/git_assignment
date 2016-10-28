<?xml version="1.0" encoding="utf-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
  <xsl:output method="xml" indent="yes"/>

  <xsl:template match="/">
    <xsl:variable name="title">
      <xsl:value-of select="info/property[@name='projectname']/@value"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="info/property[@name='label']/@value"/>
    </xsl:variable>

    <html><head>
      <title><xsl:value-of select="$title"/></title>
      <style>
body {
  font:normal 100% verdana,arial,helvetica;
  color:#000000;
}
table tr td, table tr th {
    font-size: 68%;
}
table.details tr th{
  font-weight: bold;
  text-align:left;
  background:#a6caf0;
}
table.details tr td{
  background:#eeeee0;
}

p {
  line-height:1.5em;
  margin-top:0.5em; margin-bottom:1.0em;
}
h1 {
  margin: 0px 0px 5px; font: 165% verdana,arial,helvetica
}
h2 {
  margin-top: 1em; margin-bottom: 0.5em; font: bold 125% verdana,arial,helvetica
}
h3 {
  margin-bottom: 0.5em; font: bold 115% verdana,arial,helvetica
}
h4 {
  margin-bottom: 0.5em; font: bold 100% verdana,arial,helvetica
}
h5 {
  margin-bottom: 0.5em; font: bold 100% verdana,arial,helvetica
}
h6 {
  margin-bottom: 0.5em; font: bold 100% verdana,arial,helvetica
}
.success {
  font-weight:bold; color:green;
}
.skipped {
  font-weight:bold; color:blue;
}
.error {
  font-weight:bold; color:red;
}
.failure {
  font-weight:bold; color:purple;
}
.Properties {
  text-align:right;
}

      </style>
    </head>
    <body><h1><xsl:value-of select="$title"/></h1>

    <xsl:if test="build/@error and count(build//message[@priority='warn']) > 0">
      <pre class="build">
        <xsl:apply-templates select="build//message[@priority='warn']"/>
      </pre>
    </xsl:if>

    <xsl:choose>
      <xsl:when test="testsuite">
        <xsl:apply-templates select="testsuite"/>
      </xsl:when>
      <xsl:otherwise>
        <p>Tests were not run.</p>
      </xsl:otherwise>
    </xsl:choose>

    </body></html>
  </xsl:template>

  <xsl:template match="property[@name='label']">
    <xsl:value-of select="@value"/>
  </xsl:template>

  <xsl:template match="testsuite">
    <xsl:choose>
      <xsl:when test="@failures > 0 or @errors > 0">
        <div class="largebox error">
          Tests Failed : 
          Total tests: <xsl:value-of select="@tests"/>, 
          Failures: <xsl:value-of select="@failures"/>, 
          Errors: <xsl:value-of select="@errors"/>,
          Skips: <xsl:value-of select="@skips"/>,
          (needed <xsl:value-of select="@time"/>s)
        </div>
      </xsl:when>
      <xsl:when test="@skips > 0">
        <div class="largebox failure">
          Tests Skipped : 
          Total tests: <xsl:value-of select="@tests"/>, 
          Failures: <xsl:value-of select="@failures"/>, 
          Errors: <xsl:value-of select="@errors"/>,
          Skips: <xsl:value-of select="@skips"/>,
          (needed <xsl:value-of select="@time"/>s)
        </div>
      </xsl:when>
      <xsl:otherwise>
        <div class="largebox success">
          Tests Successful : 
          Total tests: <xsl:value-of select="@tests"/>, 
          Failures: <xsl:value-of select="@failures"/>, 
          Errors: <xsl:value-of select="@errors"/>,
          Skips: <xsl:value-of select="@skips"/>,
          (needed <xsl:value-of select="@time"/>s)
        </div>
      </xsl:otherwise>
    </xsl:choose>

    <table border="1">
      <tr><th>Status</th><th>classname</th><th>name</th><th>Time (s)</th></tr>

      <xsl:for-each select="testcase[error]">
        <xsl:call-template name="testcase.error"/>
      </xsl:for-each>

      <xsl:for-each select="testcase[failure]">
        <xsl:call-template name="testcase.failure"/>
      </xsl:for-each>

      <xsl:for-each select="testcase[skipped]">
        <xsl:call-template name="testcase.skipped"/>
      </xsl:for-each>

      <xsl:for-each select="testcase">
        <xsl:choose>
          <xsl:when test="error">
          </xsl:when>
          <xsl:when test="failure">
          </xsl:when>
          <xsl:when test="skipped">
          </xsl:when>
          <xsl:otherwise>
            <xsl:call-template name="testcase.success"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>

    </table>

    <xsl:if test="system-out != '' or system-err != ''">
      <ul>
        <xsl:if test="system-out != ''">
          <li class="output">
            <p>Standard output:</p>
            <pre class="output">
              <xsl:value-of select="system-out"/>
            </pre>
          </li>
        </xsl:if>

        <xsl:if test="system-err != ''">
          <li class="output">
            <p>Standard error:</p>
            <pre class="output">
              <xsl:value-of select="system-err"/>
            </pre>
          </li>
        </xsl:if>
      </ul>
    </xsl:if>
  </xsl:template>

  <xsl:template name="testcase.error">
    <tr>
      <td><em class="error">Error</em></td>
      <td><xsl:value-of select="@classname"/></td>
      <td><xsl:value-of select="@name"/></td>
      <td><xsl:value-of select="@time"/></td>
    </tr>
    <tr>
      <td></td>
      <td colspan="3">
        <xsl:value-of select="error/@message"/>
      </td>
    </tr>
    <tr>
      <td></td>
      <td colspan="3">
        <pre class="backtrace">
          <xsl:value-of select="error"/>
        </pre>
      </td>
    </tr>
  </xsl:template>

  <xsl:template name="testcase.failure">
    <tr>
      <td><em class="failure">Failure</em></td>
      <td><xsl:value-of select="@classname"/></td>
      <td><xsl:value-of select="@name"/></td>
      <td><xsl:value-of select="@time"/></td>
    </tr>
    <tr>
      <td></td>
      <td colspan="3">
        <xsl:value-of select="failure/@message"/>
      </td>
    </tr>
    <tr>
      <td></td>
      <td colspan="3">
        <pre class="backtrace">
          <xsl:value-of select="failure"/>
        </pre>
      </td>
    </tr>
  </xsl:template>

  <xsl:template name="testcase.skipped">
    <tr>
      <td><em class="skipped">Skipped</em></td>
      <td><xsl:value-of select="@classname"/></td>
      <td><xsl:value-of select="@name"/></td>
      <td><xsl:value-of select="@time"/></td>
    </tr>
    <tr>
      <td></td>
      <td colspan="3">
        <xsl:value-of select="skipped/@message"/>
      </td>
    </tr>
  </xsl:template>

  <xsl:template name="testcase.success">
    <tr>
      <td><em class="success">Success</em></td>
      <td><xsl:value-of select="@classname"/></td>
      <td><xsl:value-of select="@name"/></td>
      <td><xsl:value-of select="@time"/></td>
    </tr>
  </xsl:template>

  <xsl:template match="message">
    <xsl:value-of select="text()"/><br/>
  </xsl:template>
</xsl:stylesheet>
