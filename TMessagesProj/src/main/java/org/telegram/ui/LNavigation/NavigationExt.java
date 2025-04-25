package org.telegram.ui.LNavigation;

import org.telegram.ui.ActionBar.BaseFragments;
import org.telegram.ui.ActionBar.INavigationLayout;

import java.util.ArrayList;
import java.util.List;

//common use cases with navigation
public class NavigationExt {

    public static boolean backToFragment(BaseFragments fragment, FragmentConsumer consumer) {
        if (fragment == null || fragment.getParentLayout() == null) {
            return false;
        }
        INavigationLayout parentLayout = fragment.getParentLayout();
        fragment = fragment.getParentLayout().getLastFragment();
        List<BaseFragments> fragmentStack = fragment.getParentLayout().getFragmentStack();
        List<BaseFragments> fragmentsToClose = new ArrayList<>();
        boolean found = false;
        for (int i = parentLayout.getFragmentStack().size() - 1; i >= 0; i--) {
            if (consumer.consume(fragmentStack.get(i))) {
                found = true;
                break;
            }
            fragmentsToClose.add(fragmentStack.get(i));
        }
        if (!found) {
            return false;
        }
        for (int i = fragmentsToClose.size() - 1; i >= 0; i--) {
            if (fragmentsToClose.get(i) != fragment) {
                fragmentsToClose.get(i).removeSelfFromStack();
            }
        }
        fragment.finishFragment();
        return true;
    }

    public interface FragmentConsumer {
        boolean consume(BaseFragments fragment);
    }
}
