Multi‑Site File Store Approach — Liferay DXP 
. Objective 
Implement a site‑specific file storage mechanism in Liferay so that Documents & Media files, previews, and Adaptive Media are stored in different physical directories Site based instead of the default single shared directory. 

. Proposed Solution 
a)We implemented a custom Store implementation that dynamically resolves the base directory using the Site GroupId.
b)We can extend FileStoargeSystem. 

But here we were having dependency issues so we move forward with (a) approach 
New storage structure: /site-{groupId}/{companyId}/{repositoryId}/... 

. Key Class / Interface Used 
com.liferay.document.library.kernel.store.Store :- This is the core Liferay storage interface responsible for all file operations such as: 
By implementing this interface and registering it as OSGi component default=true with , Liferay automatically uses the custom store instead of the default FileSystemStore. 

Component Class Created :- com.liferay.multi.site.store.system.MultiSiteStore 
Annotations used:  @Component( 
 service = Store.class, 
 property = "default=true", 
 immediate = true 
) 
