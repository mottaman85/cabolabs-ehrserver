package com.cabolabs.security

import static org.springframework.http.HttpStatus.*
import grails.transaction.Transactional
import grails.validation.ValidationException
import grails.plugin.springsecurity.SpringSecurityUtils
import com.cabolabs.security.Organization

@Transactional(readOnly = false)
class UserController {

   static allowedMethods = [save: "POST", update: "PUT", delete: "DELETE"]
   
   def simpleCaptchaService
   def notificationService
   def springSecurityService

   def index(Integer max)
   {
      params.max = Math.min(max ?: 10, 100)
      
      def list, count
      
      if (SpringSecurityUtils.ifAllGranted("ROLE_ADMIN"))
      {
         list = User.list(params)
         count = User.count()
      }
      else
      {
         // auth token used to login
         def auth = springSecurityService.authentication
         def org = Organization.findByNumber(auth.organization)
         
         // no pagination

         // users with the current org.uid in their organizations list
         
         //http://stackoverflow.com/questions/7531011/grails-gorm-criteria-query-with-hasmany-string
         list = User.withCriteria {
            createAlias('organizations', 'oo')
            eq ('oo.elements', org.uid)
         }
         
         count = list.size()
      }
      
      
      
      respond list, model:[userInstanceCount: count]
   }

   def show(User userInstance) {
      respond userInstance
   }
   
   def login()
   {
      // http://stackoverflow.com/questions/32621369/customize-login-in-grails-spring-security-plugin
   }

   /**
    * 
    * @return
    */
   def register()
   {
      println params
      
      if (!params.register) // show view
      {
        render view: "register", model: [userInstance: new User(params)]
      }
      else
      {
        boolean captchaValid = simpleCaptchaService.validateCaptcha(params.captcha)
        
        def u = new User(
          username: params.username,
          password: params.password,
          email: params.email
        )
        def o
        
        User.withTransaction{ status ->
        
          try
          {
            u.save(failOnError: true, flush:true)
            
            UserRole.create( u, (Role.findByAuthority('ROLE_USER')), true )
            
            // TODO: create an invitation with token, waiting for account confirmation
            // 
            o = new Organization(name: params.organization.name)
            o.save(failOnError: true, flush:true)
            u.addToOrganizations(o.uid).save(failOnError: true, flush:true)
            
            // TODO: UserROle ORG_* needs a reference to the org, since the user
            //      can be ORG_ADMIN in one org and ORG_STAFF in another org.
            UserRole.create( u, (Role.findByAuthority('ROLE_ORG_STAFF')), true )
            UserRole.create( u, (Role.findByAuthority('ROLE_ORG_MANAGER')), true ) // the user is creating the organization, it should be manager also
          }
          catch (ValidationException e)
          {
            println u.errors
            println o?.errors
            
            status.setRollbackOnly()
          }
          
          // FIXME: avoid saving stuff if the captcha is incorrect
          if (!captchaValid) status.setRollbackOnly()
          
        } // transaction
        
        // TODO: create a test of transactionality, were the user is saved but the org not, and check if the user is rolled back
        
        // TODO: send confirm email
        
        if (u.errors.hasErrors() || o?.errors.hasErrors() || !captchaValid)
        {
          flash.message = 'user.registerError.feedback'
          render view: "register", model: [userInstance: u, organizationInstance: o, captchaValid: captchaValid]
        }
        else
        {
          notificationService.sendUserRegisteredEmail(u.email, [o.name, o.number])
          render (view: "registerOk")
        }
      }
   }
   
   def create() {
      respond new User(params)
   }

   @Transactional
   def save(User userInstance) {
      if (userInstance == null) {
         notFound()
         return
      }

      if (userInstance.hasErrors()) {
         respond userInstance.errors, view:'create'
         return
      }

      userInstance.save flush:true

      request.withFormat {
         form multipartForm {
            flash.message = message(code: 'default.created.message', args: [message(code: 'user.label', default: 'User'), userInstance.id])
            redirect userInstance
         }
         '*' { respond userInstance, [status: CREATED] }
      }
   }

   def edit(User userInstance) {
      respond userInstance
   }

   @Transactional
   def update(User userInstance) {
      if (userInstance == null) {
         notFound()
         return
      }

      if (userInstance.hasErrors()) {
         respond userInstance.errors, view:'edit'
         return
      }

      userInstance.save flush:true

      request.withFormat {
         form multipartForm {
            flash.message = message(code: 'default.updated.message', args: [message(code: 'User.label', default: 'User'), userInstance.id])
            redirect userInstance
         }
         '*'{ respond userInstance, [status: OK] }
      }
   }

   @Transactional
   def delete(User userInstance) {

      if (userInstance == null) {
         notFound()
         return
      }

      userInstance.delete flush:true

      request.withFormat {
         form multipartForm {
            flash.message = message(code: 'default.deleted.message', args: [message(code: 'User.label', default: 'User'), userInstance.id])
            redirect action:"index", method:"GET"
         }
         '*'{ render status: NO_CONTENT }
      }
   }

   protected void notFound() {
      request.withFormat {
         form multipartForm {
            flash.message = message(code: 'default.not.found.message', args: [message(code: 'user.label', default: 'User'), params.id])
            redirect action: "index", method: "GET"
         }
         '*'{ render status: NOT_FOUND }
      }
   }
}