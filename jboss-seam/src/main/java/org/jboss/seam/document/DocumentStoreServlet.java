package org.jboss.seam.document;

import java.io.IOException;
import java.net.URLEncoder;

import javax.mail.internet.MimeUtility;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jboss.seam.servlet.ContextualHttpServletRequest;
import org.jboss.seam.web.Parameters;

public class DocumentStoreServlet extends HttpServlet
{
    private static final long serialVersionUID = 5196002741557182072L;

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
    {
        new ContextualHttpServletRequest(request) {
            @Override
            public void process() throws ServletException, IOException
            {
                doWork(request, response);
            }
        }.run();
    }

    private static void doWork(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        Parameters params = Parameters.instance();
        String contentId = (String) params.convertMultiValueRequestParameter(params.getRequestParameters(), "docId", String.class);

        DocumentStore store = DocumentStore.instance();

        if (store.idIsValid(contentId))
        {
            DocumentData documentData = store.getDocumentData(contentId);

            response.setContentType(documentData.getDocumentType().getMimeType());
            if(isInternetExplorer(request))
                response.setHeader("Content-Disposition", documentData.getDisposition() + "; filename=\"" + URLEncoder.encode(documentData.getFileName(), "utf-8") + "\"");
            else
                response.setHeader("Content-Disposition", documentData.getDisposition() + "; filename=\"" + MimeUtility.encodeWord(documentData.getFileName(), "UTF-8", "Q") + "\"");
            
            documentData.writeDataToStream(response.getOutputStream());
        }
        else
        {
            String error = store.getErrorPage();
            if (error != null)
            {
                if (error.startsWith("/"))
                {
                    error = request.getContextPath() + error;
                }
                response.sendRedirect(error);
            }
            else
            {
                response.sendError(404);
            }
        }
    }

    private static boolean isInternetExplorer(HttpServletRequest request)
    {
        String userAgent = request.getHeader("user-agent");
        return (userAgent.indexOf("MSIE") > -1);
    }
}
