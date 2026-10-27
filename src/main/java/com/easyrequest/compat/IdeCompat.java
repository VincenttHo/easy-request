package com.easyrequest.compat;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.content.ContentFactory;
import java.lang.reflect.InvocationTargetException;

/** Bridges APIs that moved between the 2019 and current platforms. */
public final class IdeCompat {
    private IdeCompat() {}

    public static <T> T service(Project project, Class<T> type) {
        return ServiceManager.getService(project, type);
    }
    public static ContentFactory contentFactory() { return ServiceManager.getService(ContentFactory.class); }

    public static void disposeEditorWith(EditorTextField field, Disposable parent) {
        try {
            EditorTextField.class.getMethod("setDisposedWith", Disposable.class).invoke(field, parent);
        } catch (NoSuchMethodException oldPlatform) {
            // In 2019 EditorTextField releases its editor through removeNotify().
            Disposer.register(parent, field::removeNotify);
        } catch (ReflectiveOperationException error) { throw failure(error); }
    }
    public static IllegalStateException failure(ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
        return new IllegalStateException("IDE compatibility adapter failed", cause);
    }
}
