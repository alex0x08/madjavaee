package com.Ox08.experiments.madjavaee;
// Common Java
import java.io.*;
import java.util.*;
import java.util.logging.*;
// CDI
import jakarta.enterprise.context.*;
import jakarta.inject.*;
// JPA
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
// JSR 303 Validation API
import jakarta.validation.constraints.*;
// JSF
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.*;
// JSR 375
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.*;
import jakarta.security.enterprise.credential.*;
import jakarta.security.enterprise.identitystore.*;
// Servlet API
import jakarta.servlet.*;
import jakarta.servlet.annotation.*;
import jakarta.servlet.http.*;
// JTA
import jakarta.transaction.Transactional;
// JAX-RS
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import jakarta.ws.rs.*;
// JAX-WS
import jakarta.jws.*;
/**
 * This is single class CRUD application, based on recent Java EE stack.
 *
 * @author <a href="mailto:alex3.145@gmail.com">Alex Chernyshev</a>
 */
// ordinary JPA entity annotations
@Entity
@Table(name = "t_records")
@NamedQueries({
    @NamedQuery(name = "MegaBean.getAllRecords",
            query = "SELECT m FROM MegaBean m order by m.id desc")
})
// CDI bean annotation, which used to register instance of this class as CDI managed bean
// This is required for EntityManager injection
@Named
// Java Faces annotation, required to trigger JSF initialization on some servers
@jakarta.faces.annotation.FacesConfig()
        //(version = FacesConfig.Version.JSF_2_3) - deprecated in Faces 4.0 and upper
// JSF annotation, required to bypass jsr299 validation see WebContainer.validateJSR299Scope
@Dependent
//@ApplicationScoped or @RequestScoped are not allowed, because of @WebFilter/@WebListener annotations presence
// Servlet 3.0 annotations
// Servlet Filter - another instance of this class will be registered as servlet filter
@WebFilter("/*")
// One more instance will be registered as servlet context listener, to be used as initialization point.
// All because we can't use @ApplicationScoped and @Observes here
@WebListener
// See JSR375 spec for details
@CustomFormAuthenticationMechanismDefinition(
        loginToContinue = @LoginToContinue(
                loginPage = "/index.xhtml?login=true",
                useForwardToLogin = false,
                errorPage = "/index.xhtml?login=true&error=true"
        )
)
// used only when embedded IdentityStore in use
@jakarta.annotation.security.DeclareRoles({"admin", "user", "demo"})
// JAX-RS annotations
@ApplicationPath("api")
@jakarta.ws.rs.Path("")
// this is required for ExceptionMapper
@jakarta.ws.rs.ext.Provider
// JAX-WS binding (SOAP) Warning: conflicts with JAX-RS on OpenLiberty and Wildfly!
//@WebService
public class MegaBean extends Application implements Serializable,
        jakarta.servlet.Filter,
        ServletContextListener,
        // Because OpenLiberty/IBM Websphere Liberty does not support  combination of
        // CustomFormAuthenticationMechanismDefinition and HttpAuthenticationMechanism,
        // I was required to remove HttpAuthenticationMechanism interface
        // Custom IdentityStore does not work without @ApplicationScoped on OpenLiberty
        IdentityStore, // see JSR375
        ExceptionMapper<Exception> {
    public MegaBean() {
        // call for JAX-RS parent class
        super();
        /*
         * we need to set some default values to bypass JSR 303 bean validation for
         * JAX-RS bean, otherwise, JAX-RS service will not work.
         */
        this.author = "no@no.org";
        this.createdAt = new Date();
        this.message = "no no no";
        this.title = "test title";
    }
    /**
     * We need to have an instance of this class as DTO - to transfer data from
     * html form
     */
    private transient MegaBean current;
    /**
     * This class is also a CDI managed bean, remember? So here we will inject
     * EntityManager
     */
    @Transient
    @PersistenceContext(unitName = "megaPU")
    private EntityManager em;
    /**
     * Security context maybe null when JAAS API was not initialized, so it's wrapped with @Instance
     */
    @Transient
    @Inject
    private jakarta.enterprise.inject.Instance<jakarta.security.enterprise.SecurityContext> securityContext;
    @Transient
    @Context
    private ServletContext servletContext;
    /**
     * Ordinary JPA fields
     */
    @Id
    @SequenceGenerator(name = "default_gen", sequenceName = "w_default_pk_seq")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "default_gen")
    private Long id; // unique id, this sequence will be created automatically too
    @Size(min = 3, max = 255)
    @Pattern(regexp = "[a-zA-Z0-9._ -?!]+")
    private String title; // used also as 'login' field for auth
    @Size(min = 3, max = 30)
    @Email
    private String author; // used also as 'password' field for auth
    @Lob
    @Column(length = Integer.MAX_VALUE)
    @NotBlank(message = "message may not be blank")
    private String message; //message body, CLOB/TEXT/BLOB type will be used in database
    @Column(name = "created_date", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    @NotNull
    protected Date createdAt;
    /**
     * this called from JSF page to clean up fields on page reload
     */
    @WebMethod(exclude = true)
    public void init() { resetFields(this); }
    /**
     * Each and every interface methods should be implemented and marked with
     * Annotation WebMethod(exclude = true) used to avoid bug in Apache CXF (Wildfly/OpenLiberty)
     * <a href="https://issues.apache.org/jira/browse/CXF-4916">...</a>
     * Method 'contextDestroyed' is part of ServletContextListener interface, so must be implemented
     */
    @WebMethod(exclude = true)
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // not used
    }
    /**
     * Part of ServletContextListener API, used on app start/reload
     */
    @Override
    @WebMethod(exclude = true)
    // transactional is required to let EntityManager do his job
    @Transactional(Transactional.TxType.REQUIRED)
    public void contextInitialized(ServletContextEvent sce) {
        final ServletContext sc = sce.getServletContext();
        // due to CDI vs servlet conflict
        sc.setAttribute("mega", this);
        // we can make it only here due to stackoverflow error in eclipselink
        this.current = new MegaBean();
        // reset fields back to nulls - to have working JSR303 validation
        resetFields(this.current);
        // populate JSF version details
        addVersionEnv(sc);
        // try to add some initial data if database is empty
        try {
            if (fetchRecordsCount() == 0) {
                //create test entity
                final MegaBean r = new MegaBean();
                r.setCreatedAt(new Date());
                r.setAuthor("system@test.org");
                r.setMessage("Test message");
                r.setTitle("Test title");
                em.merge(r);
                LOG.info(String.format("automatically added default record: %d", r.getId()));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    String.format("Exception on startup: %s", e.getMessage()), e);
        }
    }
    /**
     * JSF bean method, used to save form (from itself)
     */
    @WebMethod(exclude = true)
    @Transactional(value=Transactional.TxType.REQUIRED,rollbackOn = Exception.class)
    public String save() {
        // set creation date&time
        current.setCreatedAt(new Date());
        em.merge(current);
        // this is required to reset form fields
        this.current = new MegaBean();
        resetFields(this.current);
        // does redirect
        return "/index.xhtml?faces-redirect=true";
    }
    /**
     * Does login action from JSF page
     * @throws IOException
     *          if God was not on our side
     */
    @WebMethod(exclude = true)
    public void login() throws IOException {
        // we need to re-use 2 existing fields, present in this class: 'author for username and 'title' for password
        final Credential credential = new UsernamePasswordCredential(author, new Password(title));
        final FacesContext facesContext =FacesContext.getCurrentInstance();
        final ExternalContext ec =  facesContext.getExternalContext();
        // should not happen, this is used to avoid class cast
        if (!(ec.getRequest() instanceof HttpServletRequest req)
                || !(ec.getResponse() instanceof HttpServletResponse res)) {
            ec.getRequestMap().put("login", "true");
            facesContext.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Login failed", null));
            return;
        }
        // check if JAAS initialized
        if (!securityContext.isResolvable()) {
            LOG.warning("SecurityContext cannot be resolved!");
            return;
        }
        // try to authenticate programmatically
        final AuthenticationStatus status = securityContext.get()
                .authenticate(
                        req, res,
                        AuthenticationParameters.withParams().credential(credential));
        if (status == null) {
            LOG.warning("JAAS not initialized!");
            return;
        }
        LOG.fine(String.format("auth status: %s",status));
        switch (status) {
            case SEND_CONTINUE: {
                facesContext.responseComplete();
                break;
            }
            case SEND_FAILURE: {
                ec.getRequestMap().put("login", "true");
                facesContext.addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Login failed", null));
                break;
            }
            case SUCCESS: {
                putCurrentUser(current);
                LOG.info(String.format("logged in as %s",current.author));
                facesContext.addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Login succeed", null));
                // after redirect there will be full page reload
                ec.redirect(ec.getRequestContextPath() + "/index.xhtml?ok=true");
                break;
            }
            case NOT_DONE:
                facesContext.responseComplete();
                break;
        }
    }
    /**
     * Does logout action from JSF page
     *
     * @return
     * @throws ServletException
     */
    @WebMethod(exclude = true)
    public String logout() throws ServletException {
        final FacesContext facesContext =FacesContext.getCurrentInstance();
        final ExternalContext ec = facesContext.getExternalContext();
        // check for impossible state
        if (!(ec.getRequest() instanceof HttpServletRequest req)) {
            facesContext.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Logout failed", null));
            return "";
        }
        req.logout();
        ec.invalidateSession();
        return "/index.xhtml?faces-redirect=true";
    }
    /**
     * JPA Entity fields
     * -------------------------------------------------------------------------------------------
     */
    @WebMethod(exclude = true)
    public String getAuthor() { return author; }
    @WebMethod(exclude = true)
    public void setAuthor(String author) { this.author = author; }
    @WebMethod(exclude = true)
    @jakarta.json.bind.annotation.JsonbTransient
    public MegaBean getCurrent() {
        return current;
    }
    @WebMethod(exclude = true)
    public Date getCreatedAt() {
        return createdAt;
    }
    @WebMethod(exclude = true)
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
    @WebMethod(exclude = true)
    public Long getId() {
        return id;
    }
    @WebMethod(exclude = true)
    public void setId(Long id) {
        this.id = id;
    }
    @WebMethod(exclude = true)
    public String getTitle() {
        return title;
    }
    @WebMethod(exclude = true)
    public void setTitle(String title) { this.title = title; }
    @WebMethod(exclude = true)
    public String getMessage() { return message; }
    @WebMethod(exclude = true)
    public void setMessage(String message) { this.message = message; }
    /**
     * JAX-RS & JAX-WS Methods
     * -----------------------------------------------------------
     * Each method serves for both APIs
     * Ping is a test method, which respond plain text
     */
    @GET
    @jakarta.ws.rs.Path("ping")
    @Produces(MediaType.TEXT_PLAIN)
    @WebMethod
    public String doPing() { return "pong: " + System.currentTimeMillis(); }
    /**
     * Adds new message to guestbook from API
     * @param dto
     *          new message data
     * @return
     */
    @WebMethod
    @POST
    @jakarta.ws.rs.Path("addMessage")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    // for JAX-WS only
    @Transactional
    public String addMessage(MegaBean dto) {
        LOG.info(String.format("prepare to add record %s , %s , %s", author, title, message));
        final MegaBean r = new MegaBean();
        r.setCreatedAt(new Date());
        r.setAuthor(dto.author);
        r.setMessage(dto.message);
        r.setTitle(dto.title);
        // for JAX-RS, EntityManager should be injected
        if (em!=null && em.isJoinedToTransaction())
            return addMessageImpl(r);
        // otherwise, take EntityManager from servlet context
        // note: access to servletContext from JAX-RS will trigger exception:
        // RESTEASY003880: Unable to find contextual data of type: jakarta.servlet.ServletContext
        else {
            final MegaBean mb = (MegaBean) servletContext.getAttribute("mega");
            return mb.addMessageImpl(r);
        }
    }

    /**
     * This 'black magic' is required, because JAX-WS does not allow transaction injection on service method
     */
    @Transactional(Transactional.TxType.REQUIRED)
    @WebMethod(exclude = true)
    public String addMessageImpl(MegaBean r) {
        try {
            r=em.merge(r);
            LOG.info(String.format("saved record %d", r.id));
            return String.format("Message added: %d %n", r.id);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            return String.format("Error on saving: %s", e.getMessage());
        }
    }

    /**
     * Uses Criteria API to retrieve count of records
     */
    @WebMethod
    @GET
    @jakarta.ws.rs.Path("recordsCount")
    @Produces(MediaType.TEXT_PLAIN)
    public Long fetchRecordsCount() {
        final EntityManager em = selectEm();
        final CriteriaBuilder qb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> cq = qb.createQuery(Long.class);
        cq.select(qb.count(cq.from(MegaBean.class)));
        return em.createQuery(cq).getSingleResult();
    }

    /**
     * API method to retrieve all guestbook records
     */
    @WebMethod
    @GET
    @jakarta.ws.rs.Path("records")
    @Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
    public List<MegaBean> fetchRecords() {
        return selectEm().createNamedQuery("MegaBean.getAllRecords", MegaBean.class).getResultList();
    }

    /**
     * API method to get currently authenticated user details
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
    @jakarta.ws.rs.Path("details")
    @WebMethod(exclude = true)
    public Response userDetails(@Context SecurityContext sc) {
        final java.security.Principal p = sc.getUserPrincipal(); // see sc.getCallerPrincipal() in Jakarta EE;
        return p != null ? Response.ok(p.getName()).build() :
                Response.status(Response.Status.UNAUTHORIZED).build();
    }
    /**
     * Methods below are required , due to re-use of same class for both JAX-WS
     * and JAX-RS
     * -------------------------------------------------------------------------------------------------------
     *
     */
    // part of IdentityStore API, not used
    @Override
    @WebMethod(exclude = true)
    public Set<String> getCallerGroups(CredentialValidationResult validationResult) {
        return Collections.emptySet();
    }
    @Override
    @WebMethod(exclude = true)
    public int priority() {
        return 100;
    }
    @Override
    @WebMethod(exclude = true)
    public Set<ValidationType> validationTypes() {
        return DEFAULT_VALIDATION_TYPES;
    }
    @WebMethod(exclude = true)
    @Override
    public void init(FilterConfig filterConfig) { }
    @WebMethod(exclude = true)
    @Override
    public void destroy() { }
    /**
     * this filter is used to redirect from / to actual jsf page
     *
     */
    @Override
    @WebMethod(exclude = true)
    public void doFilter(ServletRequest sr, ServletResponse sr1, FilterChain fc)
            throws IOException, ServletException {
        final HttpServletRequest request = (HttpServletRequest) sr;
        LOG.info(String.format("got request: %s", request.getRequestURI()));
        // required for correct characters encoding
        request.setCharacterEncoding("UTF-8");
        final String p = request.getRequestURI(),
                cp = request.getServletContext().getContextPath();
        String url = p;
        if (p.startsWith(cp)) url = p.substring(cp.length());
        if ("/".equals(url) && sr1 instanceof HttpServletResponse hsr)
            hsr.sendRedirect(cp + "/index.xhtml");
        else fc.doFilter(sr, sr1);
    }
    /**
     * Custom JSR375 validation
     * Used in combination with IdentityStore
     * @param credential
     * @return 
     */
    @Override
    @WebMethod(exclude = true)
    public CredentialValidationResult validate(Credential credential) {
        // should not happen
        if (!(credential instanceof UsernamePasswordCredential userCredential))
            return CredentialValidationResult.INVALID_RESULT;
        final String login = userCredential.getCaller();
        LOG.info(String.format("called validate for %s", login));
        if (!USERS.containsKey(login))
            return CredentialValidationResult.INVALID_RESULT;
        final Map<String, Object> user = USERS.get(login);
        // dumb password check
        if (!userCredential.compareTo(login, (String) user.get("password")))
            return CredentialValidationResult.INVALID_RESULT;
        LOG.info(String.format("user %s validated", login));
        return new CredentialValidationResult(login,
                new HashSet<>(Arrays.asList((String[]) user.get("roles"))));
    }

    /**
     * JAX-RS exception handler
     * @param e
     * @return
     */
    @Override
    @WebMethod(exclude = true)
    public Response toResponse(Exception e) {
        LOG.log(Level.WARNING, String.format("Exception on call : %s", e.getMessage()), e);
        return Response.status(400).entity(e.getMessage())
                .type("text/plain").build();
    }
    // !! required for YASSON parser, otherwise exception will raise:
    //  Error accessing getter 'getEnclosingConstructor' declared in 'class java.lang.Class'
    @Override
    @WebMethod(exclude = true)
    @jakarta.json.bind.annotation.JsonbTransient
    public Set<Class<?>> getClasses() { return Collections.emptySet();}

    /*
        remove from JAX-RS/JAX-WS output
    */
    @Override
    @WebMethod(exclude = true)
    @jakarta.json.bind.annotation.JsonbTransient
    public Set<Object> getSingletons() { return Collections.emptySet();}
    /*
        remove from JAX-RS/JAX-WS output
    */
    @Override
    @WebMethod(exclude = true)
    @jakarta.json.bind.annotation.JsonbTransient
    public Map<String,Object> getProperties() { return Collections.emptyMap();}
    /**
     * Clean fields for provided instance
     * @param m
     *          bean instance
     */
    private void resetFields(MegaBean m) {
        m.setAuthor(null);
        putCurrentUser(m);
        m.setCreatedAt(null);
        m.setId(null);
        m.setMessage(null);
        m.setTitle(null);
    }
    /**
     * JAX-RS and JAX-WS APIs have different lifecycle, for JAX-WS, an EntityManager will be injected by CDI,
     *  but for JAX-RS is not (not for all servers).
     *  So we need some selection logic here
     */
    private EntityManager selectEm() {
        // if EntityManager was not injected
        if (this.em!=null) return this.em;
        // take instance from servlet context
        return ((MegaBean) servletContext.getAttribute("mega")).em;
    }
    /**
     * Get current user from principal
     * @return
     *          current user's name
     */
    public static String getCurrentUser() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        // if there is no faces context - could happen if current bean was not created by JSF
        if (ctx==null || ctx.getExternalContext()==null)
            return null;
        // get principal (the standard way) from current context
        final java.security.Principal p = ctx.getExternalContext().getUserPrincipal();
        return p == null ? null : p.getName();
    }
    /**
     * Set current user's name to author field of our bean instance
     * @param instance
     *          an instance of MegaBean, used as DTO
     */
    private static void putCurrentUser(MegaBean instance) {
        final String username = getCurrentUser();
        if (username!=null)
            instance.setAuthor(username);
    }
    /**
     * Reads JSF version details and store as attribute of ServletContext
     * @param sc
     */
    private static void addVersionEnv(ServletContext sc) {
        final Package facesPackage = FacesContext.class.getPackage();
        final StringBuilder sb = new StringBuilder();
        if (sc.getServerInfo() !=null)
            sb.append(sc.getServerInfo());
        if (facesPackage.getImplementationVersion()!=null)
            sb.append(facesPackage.getImplementationVersion());
        sc.setAttribute("versionLine", sb.toString());
        LOG.info(sb.toString());
    }
    // credentials store, not used under Wildfly/OpenLiberty
    private static final Map<String, Map<String, Object>> USERS = new TreeMap<>();
    static {
        final Map<String, Object> admin_user = new HashMap<>();
        admin_user.put("password", "admin");
        admin_user.put("roles", new String[]{"admin", "user", "demo"});
        USERS.put("admin@test.org", admin_user);
        final Map<String, Object> s_user = new HashMap<>();
        s_user.put("password", "user");
        s_user.put("roles", new String[]{"user"});
        USERS.put("user@test.org", s_user);
    }
     // ordinary JUL logger, will not be serialized/persisted
    private static final Logger LOG = Logger.getLogger("MEGA");
}
