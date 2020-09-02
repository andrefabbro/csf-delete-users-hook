package br.com.ext.portal.hooks;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.liferay.portal.kernel.concurrent.ThreadPoolExecutor;
import com.liferay.portal.kernel.events.ActionException;
import com.liferay.portal.kernel.events.SimpleAction;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.executor.PortalExecutorManagerUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.SearchEngineUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.model.Portlet;
import com.liferay.portal.model.User;
import com.liferay.portal.security.permission.PermissionChecker;
import com.liferay.portal.security.permission.PermissionCheckerFactoryUtil;
import com.liferay.portal.security.permission.PermissionThreadLocal;
import com.liferay.portal.service.PortletLocalServiceUtil;
import com.liferay.portal.service.UserLocalServiceUtil;
import com.liferay.portal.service.persistence.BatchSessionUtil;

/**
 * @author Andre Fabbro
 *
 */
public class StartupActionHook extends SimpleAction implements Serializable {

	private static final long serialVersionUID = -1114838293787661137L;

	private static Log _log = LogFactoryUtil.getLog(StartupActionHook.class);

	// private final int _number_of_cores =
	// Runtime.getRuntime().availableProcessors();
	private final int _number_of_cores = 24;

	private static final String ADMIN_USER_ID_PROPERTY = "br.com.gndi.admin.user.id";

	class DoTheDirtyWork implements Runnable {

		protected int end;
		protected ThreadPoolExecutor executor;
		protected PermissionChecker permissionChecker;
		protected int start;
		protected Set<String> keepEmailAddresses;
		protected Date lastLoginDate;

		public DoTheDirtyWork(int start, int end, PermissionChecker permissionChecker, ThreadPoolExecutor executor,
				Set<String> keepEmailAddresses, Date lastLoginDate) {

			this.start = start;
			this.end = end;
			this.permissionChecker = permissionChecker;
			this.executor = executor;
			this.keepEmailAddresses = keepEmailAddresses;
			this.lastLoginDate = lastLoginDate;
		}

		@Override
		public void run() {

			PermissionThreadLocal.setPermissionChecker(permissionChecker);

			_log.info("Number of active threads: " + executor.getActiveCount());
			_log.info("Deleting users from " + start + " to " + end);

			try {

				// near:46460303
				// stream:gs-consulting
				// topic:SQL+script+to+delete+users+from+lfr
				SearchEngineUtil.setIndexReadOnly(true);

				List<User> users = UserLocalServiceUtil.getUsers(start, end);

				for (User user : users) {

					try {

						boolean found = false;
						for (String email : keepEmailAddresses) {
							if (email.trim().equalsIgnoreCase(user.getEmailAddress().trim())) {
								found = true;
								break;
							}
						}

						if (lastLoginDate != null && user.getLastLoginDate() != null) {
							if (lastLoginDate.before(user.getLastLoginDate()))
								found = true;
						}

						if (!found) {
							// UserLocalServiceUtil.deleteUser(user.getUserId());
							UserLocalServiceUtil.deleteUser(user);
						}

					} catch (SystemException | PortalException e) {
						_log.error(e.getMessage());
						e.printStackTrace();
					} catch (Exception e) {
						_log.error(e.getMessage());
						e.printStackTrace();
					}
				}

			} catch (SystemException e) {
				_log.error(e.getMessage());
				e.printStackTrace();
			}

		}

	}

	@Override
	public void run(String[] ids) throws ActionException {
		_log.info("Inicio do processo para exclusao de usuarios do banco");

		final String[] keepu = GetterUtil.getStringValues(PropsUtil.getArray("delete.users.hook.config.keep.users"));
		final Date lastLoginDate = GetterUtil.getDate(PropsUtil.get("gndi.delete.users.lastlogin"),
				new SimpleDateFormat("yyyyMMdd"));

		User user = null;
		try {
			user = UserLocalServiceUtil.getUser(GetterUtil.getLong(PropsUtil.get(ADMIN_USER_ID_PROPERTY)));
		} catch (PortalException | SystemException e) {
			_log.error(e.getMessage());
			e.printStackTrace();
			return;
		}

		Set<String> admins = new HashSet<String>();
		admins.add("default@liferay.com");
		admins.add("test@liferay.com");
		admins.add("andre.fabbro@liferay.com");
		admins.add(user.getEmailAddress());

		for (int i = 0; i < keepu.length; i++)
			admins.add(keepu[i].trim());

		try {

			PermissionChecker permissionChecker = PermissionCheckerFactoryUtil.create(user);

			BatchSessionUtil.setEnabled(true);

			SearchEngineUtil.setIndexReadOnly(true);

			List<Portlet> portlets = PortletLocalServiceUtil.getPortlets(user.getCompanyId());

			_log.info("Desativando: " + portlets.size() + " portlets");

			for (Portlet portlet : portlets) {
				if (portlet.isActive())
					portlet.setActive(false);
			}

			_log.info("Portlets desativados");

			int batchSize = 2000;

			int count = UserLocalServiceUtil.getUsersCount();

			int pages = count / batchSize;

			ThreadPoolExecutor executor = PortalExecutorManagerUtil
					.getPortalExecutor(StartupActionHook.class.getName());

			executor.adjustPoolSize(_number_of_cores, _number_of_cores);

			for (int i = 0; i <= pages; i++) {
				int start = (i * batchSize);
				int end = start + batchSize - 1;

				executor.execute(new DoTheDirtyWork(start, end, permissionChecker, executor, admins, lastLoginDate));
			}

			_log.info("Number of tasks to process: " + executor.getTaskCount());

		} catch (Exception e) {
			_log.error(e.getMessage());
			e.printStackTrace();
		}
	}
}
