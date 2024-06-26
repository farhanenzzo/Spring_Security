package spring.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;


import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@SpringBootApplication
public class SecurityApplication {

	public static void main(String[] args) {
		SpringApplication.run(SecurityApplication.class, args);
	}

	// Creating a bean for password encryption
	@Bean
	public BCryptPasswordEncoder getBCryptPasswordEncoder() {
		return new BCryptPasswordEncoder();
	}
}

@RestController
class BasicController {

	@Autowired
	private JwtUtil jwtUtil;

	// injecting authentication manager
	@Autowired
	private AuthenticationManager authenticationManager;

	@PostMapping("login")
	public ResponseEntity<String> login(@RequestBody LoginRequestDTO loginRequestDTO) {
		// Creating UsernamePasswordAuthenticationToken object
		// to send it to authentication manager.
		// Attention! We used two parameters constructor.
		// It sets authentication false by doing this.setAuthenticated(false);
		UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(loginRequestDTO.getUsername(), loginRequestDTO.getPassword());
		// we let the manager do its job.
		authenticationManager.authenticate(token);
		// if there is no exception thrown from authentication manager,
		// we can generate a JWT token and give it to user.
		String jwt = jwtUtil.buildToken(loginRequestDTO.getUsername());
		return ResponseEntity.ok(jwt);
	}

	@GetMapping("/hello")
	public ResponseEntity<String> get(){
		return ResponseEntity.ok("Hello");
	}
}

@NoArgsConstructor
class LoginRequestDTO {
	private String username;
	private String password;

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}
}

/*
 * This is Spring Security configuration step
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class WebSecurityConfiguration{

	// Custom filter
	@Autowired
	private JwtTokenFilter jwtTokenFilter;

	// Custom UserDetailsService
	@Autowired
	private UserDetailsService userDetailsService;

	@Autowired
	BCryptPasswordEncoder passwordEncoder;

	@Autowired
	public void configurePasswordEncoder(AuthenticationManagerBuilder builder) throws Exception {
		// adding custom UserDetailsService and encryption bean to Authentication Manager
		builder.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder);
	}

	@Bean
	public AuthenticationManager getAuthenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
		return authenticationConfiguration.getAuthenticationManager();
	}

	@Bean
	protected SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable) 	// disabling csrf since we won't use form login

			.authorizeHttpRequests(req -> req
					.requestMatchers("/login").permitAll() // giving every permission to every request for /login endpoint
					.requestMatchers("/hello").authenticated() // for "/hello", the user has to be authenticated
			)

			// setting stateless session, because we choose to implement Rest API
			.sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
			// adding the custom filter before UsernamePasswordAuthenticationFilter in the filter chain
			.addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}


/*
 * Custom filter will run once per request. We add this to Filter Chain
 */

// todo : Authorization Format --> Bearer-space-(token)
@Component
class JwtTokenFilter extends OncePerRequestFilter {

	// Simple JWT implementation
	@Autowired
	private JwtUtil jwtUtil;

	// Spring Security will call this method during filter chain execution
	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest httpServletRequest,
									@NonNull HttpServletResponse httpServletResponse,
									@NonNull FilterChain filterChain) throws ServletException, IOException {

		// trying to find Authorization header
		final String authorizationHeader = httpServletRequest.getHeader("Authorization");
		if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer")){
			// if Authorization header does not exist, then skip this filter
			// and continue to execute next filter class
			filterChain.doFilter(httpServletRequest, httpServletResponse);
			return;
		}

		final String token = authorizationHeader.split(" ")[1].trim();
		if (!jwtUtil.validate(token)) {
			// if token is not valid, then skip this filter
			// and continue to execute next filter class.
			// This means authentication is not successful since token is invalid.
			filterChain.doFilter(httpServletRequest, httpServletResponse);
			return;
		}

		// Authorization header exists, token is valid. So, we can authenticate.
		String username = jwtUtil.getUsername(token);
		// initializing UsernamePasswordAuthenticationToken with its 3 parameter constructor
		// because it sets super.setAuthenticated(true); in that constructor.
		UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(username, null, new ArrayList<>());
		authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(httpServletRequest));
		// finally, give the authentication token to Spring Security Context
		SecurityContextHolder.getContext().setAuthentication(authToken);

		// end of the method, so go for next filter class
		filterChain.doFilter(httpServletRequest, httpServletResponse);
	}
}


/*
 * Custom UserDetailsService implementation
 */
@Service
class UserDetailsService implements org.springframework.security.core.userdetails.UserDetailsService {
	@Autowired
	BCryptPasswordEncoder passwordEncoder;

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		// This is where you should fetch the user from database.
		// We keep it simple to focus on authentication flow.
		Map<String, String> users = new HashMap<>();
		users.put("farhan", passwordEncoder.encode("123"));
		if (users.containsKey(username))
			return new User(username, users.get(username), new ArrayList<>());
		// if this is thrown, then we won't generate JWT token.
		throw new UsernameNotFoundException(username);
	}
}