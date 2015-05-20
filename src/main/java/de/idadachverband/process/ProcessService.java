package de.idadachverband.process;

import de.idadachverband.archive.ArchiveException;
import de.idadachverband.archive.ArchiveService;
import de.idadachverband.archive.Directories;
import de.idadachverband.archive.IdaInputArchiver;
import de.idadachverband.archive.ProcessFileConfiguration;
import de.idadachverband.archive.bean.VersionOrigin;
import de.idadachverband.institution.IdaInstitutionBean;
import de.idadachverband.job.JobCallable;
import de.idadachverband.job.JobExecutionService;
import de.idadachverband.solr.SolrUpdateService;
import de.idadachverband.solr.SolrService;
import de.idadachverband.transform.IdaTransformer;
import de.idadachverband.transform.TransformationBean;
import de.idadachverband.transform.xslt.WorkingFormatToSolrDocumentTransformer;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Named;
import javax.xml.transform.TransformerException;

import org.apache.solr.client.solrj.SolrServerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by boehm on 09.10.14.
 */
@Named
@Slf4j
public class ProcessService
{
    final private JobExecutionService jobExecutionService;
    
    final private IdaInputArchiver idaInputArchiver;

    final private WorkingFormatToSolrDocumentTransformer workingFormatTransformer;

    final private ProcessFileConfiguration processFileConfiguration;
    
    final private ArchiveService archiveService;

    final private SolrUpdateService solrUpdateService;
    
    


    @Inject
    public ProcessService(IdaTransformer transformationStrategy,
                               WorkingFormatToSolrDocumentTransformer workingFormatTransformer,
                               IdaInputArchiver idaInputArchiver,
                               ProcessFileConfiguration processFileConfiguration,
                               ArchiveService archiveService,
                               SolrUpdateService solrUpdateService,
                               JobExecutionService jobExecutionService)
    {
        this.idaInputArchiver = idaInputArchiver;
        this.workingFormatTransformer = workingFormatTransformer;
        this.processFileConfiguration = processFileConfiguration;
        this.archiveService = archiveService;
        this.solrUpdateService = solrUpdateService;
        this.jobExecutionService = jobExecutionService;
    }

    public ProcessJobBean processAsync(final Path input, final IdaInstitutionBean institution, final SolrService solr, String originalFileName, boolean incrementalUpdate) throws IOException
    {
        log.info("Start processing of: {} for institution: {} on Solr core: {}", originalFileName, institution, solr);
        final ProcessJobBean processJobBean = new ProcessJobBean(
                new TransformationBean(solr, institution, input, incrementalUpdate));
        processJobBean.setJobName(String.format("Process file %s for %s, %s", 
                originalFileName, solr.getName(), institution.getInstitutionName()));
        
        jobExecutionService.executeAsynchronous(processJobBean, new JobCallable<ProcessJobBean>()
        {
            @Override
            public void call(ProcessJobBean jobBean) throws Exception
            {
                process(jobBean.getTransformation(), jobBean.getUser().getUsername());
            }
        });
        return processJobBean;
    }
    
    /**
     * @param input              The input file to process
     * @param institution        The institution to which the file belongs
     * @param solr               The solr instance to update
     * @param transformationBean Bean holding process information
     * @return
     * @throws IOException 
     * @throws TransformerException 
     * @throws SolrServerException 
     * @throws ArchiveException 
     */
    public void process(TransformationBean transformationBean, String username) throws TransformerException, IOException, SolrServerException, ArchiveException
    {
        final String key = transformationBean.getKey();
        try
        {
            transform(transformationBean);
            
            solrUpdateService.updateSolr(transformationBean, true);

            archiveService.archive(transformationBean, VersionOrigin.UPLOAD, username);
            
        } finally
        {
            deleteProcessingFolder(key);
            Files.deleteIfExists(transformationBean.getTransformationInput());
        }
    }
    
    public void transform(TransformationBean transformationBean) throws IOException, TransformerException
    {
        final String key = transformationBean.getKey();
        final IdaInstitutionBean institution = transformationBean.getInstitution();
        try
        {
            Path path = idaInputArchiver.uncompressFile(
                    transformationBean.getTransformationInput(), 
                    processFileConfiguration.getFolder(ProcessStep.upload, key));

            path = transformToWorkingFormat(path, institution, key);
            
            path = transformToSolrFormat(path, institution, key);
            
            transformationBean.setSolrInput(path);

        } finally
        {
            final String transformationMessagesFromUpload = institution.getTransformationStrategy().getTransformationMessages();
            final String transformationMessagesToSolrFormat = workingFormatTransformer.getTransformationMessages();
            transformationBean.setTransformationWorkingFormatMessages(transformationMessagesFromUpload);
            transformationBean.setTransformationSolrFormatMessages(transformationMessagesToSolrFormat);
        }
    }
    
    private Path transformToWorkingFormat(Path inputFile, IdaInstitutionBean institution, String key) throws TransformerException, IOException
    {
        log.info("Start transformation of: {} for: {} to working format", inputFile, institution);
        final long start = System.currentTimeMillis();
        Path workingFormatFile = processFileConfiguration.getFolder(ProcessStep.workingFormat, key).resolve(inputFile.getFileName());
        Files.createDirectories(workingFormatFile.getParent());
        log.debug("Transform: {} to: {}", inputFile, workingFormatFile);
        IdaTransformer transformationStrategy = institution.getTransformationStrategy();
        transformationStrategy.transform(inputFile, workingFormatFile, institution);
        final long end = System.currentTimeMillis();
        log.info("Transformation of: {} for: {} to working format took: {} seconds", inputFile, institution, (end - start) / 1000);
        return workingFormatFile;
    }

    private Path transformToSolrFormat(Path inputFile, IdaInstitutionBean institution, String key) throws TransformerException, IOException
    {
        log.info("Start transformation of: {} for: {} to Solr format", inputFile, institution);
        final long start = System.currentTimeMillis();
        Path solrFormatFile = processFileConfiguration.getFolder(ProcessStep.solrFormat, key).resolve(inputFile.getFileName());
        Files.createDirectories(solrFormatFile.getParent());
        log.debug("Transform: {} to: {}", inputFile, solrFormatFile);
        workingFormatTransformer.transform(inputFile, solrFormatFile, institution);
        final long end = System.currentTimeMillis();
        log.info("Transformation of: {} for: {} to Solr format took: {} seconds", inputFile, institution, (end - start) / 1000);
        return solrFormatFile;
    }
    
    public void deleteProcessingFolder(String key)
    {
        final Path path = processFileConfiguration.getBasePath().resolve(key);
        log.debug("Delete processing folder: {} for transformation: {}", path, key);
        Directories.delete(path);
    }

    
}
