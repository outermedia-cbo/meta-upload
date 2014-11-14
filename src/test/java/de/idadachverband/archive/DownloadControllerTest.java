package de.idadachverband.archive;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.io.FileSystemResource;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileNotFoundException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DownloadControllerTest
{
    @InjectMocks
    private DownloadController cut;

    @Mock
    private HashedFileService hashedFileService;

    @Mock
    private HttpServletResponse response;

    @BeforeMethod
    public void setUp() throws Exception
    {
        MockitoAnnotations.initMocks(this);
    }

    @Test(expectedExceptions = FileNotFoundException.class)
    public void downloadEmptyFilename() throws Exception
    {
        cut.download(" ", response);
    }

    @Test
    public void downloadSuccess() throws Exception
    {
        when(hashedFileService.findFile("name")).thenReturn(mock(File.class));

        FileSystemResource actual = cut.download("name", response);
        assertThat(actual, notNullValue());
    }

    @Test(expectedExceptions = FileNotFoundException.class)
    public void downloadFailure() throws Exception
    {
        when(hashedFileService.findFile("name")).thenThrow(FileNotFoundException.class);
        cut.download("name", response);
    }

}